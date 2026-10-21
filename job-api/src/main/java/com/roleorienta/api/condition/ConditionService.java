package com.roleorienta.api.condition;

import com.roleorienta.api.condition.ConditionDtos.ConditionRequest;
import com.roleorienta.api.condition.ConditionDtos.ConditionResponse;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Условия поиска пользователя (бизнес-описание §3; технический документ §7 «Смена условий»).
 */
@Service
public class ConditionService {

    private final SearchConditionRepository conditions;
    private final PositionRepository positions;
    private final ApiProperties properties;

    /**
     * @param conditions версии условий
     * @param positions  позиции словаря
     * @param properties поддерживаемые страны, лимит по умолчанию
     */
    public ConditionService(SearchConditionRepository conditions, PositionRepository positions,
            ApiProperties properties) {
        this.conditions = conditions;
        this.positions = positions;
        this.properties = properties;
    }

    /**
     * Условия, сохранённые версией.
     *
     * @param condition версия
     * @param etag      версия для {@code ETag}
     */
    public record Saved(ConditionResponse condition, String etag) {
    }

    /**
     * @param userId пользователь
     * @return активная версия условий; пусто — условия не заданы
     */
    @Transactional(readOnly = true)
    public Optional<Saved> current(long userId) {
        return conditions.findByUserIdAndActiveTrue(userId).map(this::saved);
    }

    /**
     * Сохраняет условия одной транзакцией под блокировкой активной версии. Условий нет — первая
     * версия (без {@code If-Match}). Есть — нужна текущая версия: изменились страны, набор позиций или
     * формат — прежняя версия гасится и создаётся новая с пустым накопленным списком; изменился только
     * лимит — меняется в той же версии, список не сбрасывается. Статусы вакансий не затрагиваются.
     *
     * @param userId  пользователь
     * @param request условия
     * @param ifMatch версия из {@code If-Match}; {@code null} — не указана
     * @return сохранённые условия
     * @throws InvalidFieldException      неподдерживаемая страна, неизвестная позиция или выключенная позиция, которой нет
     *                                    в текущих условиях ({@code /positions})
     * @throws ConditionVersionException  версия не указана ({@code 428}) или не та ({@code 412})
     */
    @Transactional
    public Saved save(long userId, ConditionRequest request, String ifMatch) {
        List<String> countries = countries(request.countries());
        int limit = request.portionLimit() == null ? properties.defaultPortionLimit() : request.portionLimit();
        Optional<SearchCondition> active = conditions.findActiveForUpdate(userId);
        Set<Long> positionIds = positionIds(request.positions(),
                active.map(SearchCondition::getPositionIds).orElse(Set.of()));
        if (active.isEmpty()) {
            if (ifMatch != null) {
                throw new ConditionVersionException(false);
            }
            return saved(conditions.save(new SearchCondition(userId, countries, positionIds, request.format(), limit)));
        }
        SearchCondition current = active.get();
        if (ifMatch == null) {
            throw new ConditionVersionException(true);
        }
        if (!ifMatch.equals(current.etag())) {
            throw new ConditionVersionException(false);
        }
        if (current.sameSelection(countries, positionIds, request.format())) {
            current.setPortionLimit(limit);
            return saved(conditions.saveAndFlush(current));
        }
        current.deactivate();
        conditions.saveAndFlush(current);
        return saved(conditions.save(new SearchCondition(userId, countries, positionIds, request.format(), limit)));
    }

    /**
     * Позиции по кодам (без повторов); каждая — из словаря и включена, кроме уже выбранных в текущих условиях:
     * выключенная позиция остаётся у тех, кто её выбрал, но заново не выбирается (§160).
     */
    private Set<Long> positionIds(List<String> codes, Set<Long> kept) {
        Set<String> requested = codes.stream().map(String::strip).collect(Collectors.toSet());
        List<Position> found = positions.findByCodeIn(requested);
        if (found.size() != requested.size()) {
            Set<String> known = found.stream().map(Position::getCode).collect(Collectors.toSet());
            throw new InvalidFieldException("/positions", "Unknown positions: "
                    + requested.stream().filter(code -> !known.contains(code)).sorted().toList());
        }
        List<String> retired = found.stream().filter(position -> !position.isActive() && !kept.contains(position.getId()))
                .map(Position::getCode).sorted().toList();
        if (!retired.isEmpty()) {
            throw new InvalidFieldException("/positions", "Retired positions: " + retired);
        }
        return found.stream().map(Position::getId).collect(Collectors.toSet());
    }

    /**
     * Страны в верхнем регистре, без повторов, по порядку; каждая — поддерживаемая.
     */
    private List<String> countries(List<String> requested) {
        List<String> countries = requested.stream().map(country -> country.strip().toUpperCase(Locale.ROOT))
                .distinct().sorted().toList();
        if (!properties.countries().containsAll(countries)) {
            throw new InvalidFieldException("/countries", "Supported countries: " + properties.countries());
        }
        return countries;
    }

    private Saved saved(SearchCondition condition) {
        List<String> codes = positions.findAllById(condition.getPositionIds()).stream().map(Position::getCode)
                .sorted().toList();
        return new Saved(new ConditionResponse(condition.getCountries(), codes, condition.getWorkFormat(),
                condition.getPortionLimit()), condition.etag());
    }
}
