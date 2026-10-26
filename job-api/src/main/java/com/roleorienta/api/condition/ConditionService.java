package com.roleorienta.api.condition;

import com.roleorienta.api.condition.ConditionDtos.ChosenPosition;
import com.roleorienta.api.condition.ConditionDtos.ConditionRequest;
import com.roleorienta.api.condition.ConditionDtos.ConditionResponse;
import com.roleorienta.api.condition.SearchCondition.Selection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Условия поиска пользователя (бизнес-описание §3; технический документ §7 «Смена условий»).
 */
@Service
public class ConditionService {

    /** Код группы ISCO-08 в условиях (§204); остальные коды — позиции словаря (буквы, цифры и дефисы, V65). */
    private static final Pattern GROUP = Pattern.compile("[0-9]{4}");

    private final SearchConditionRepository conditions;
    private final PositionRepository positions;
    private final OccupationRepository occupations;
    private final ApiProperties properties;

    /**
     * @param conditions  версии условий
     * @param positions   позиции словаря
     * @param occupations группы ISCO-08
     * @param properties  поддерживаемые страны, лимит по умолчанию
     */
    public ConditionService(SearchConditionRepository conditions, PositionRepository positions,
            OccupationRepository occupations, ApiProperties properties) {
        this.conditions = conditions;
        this.positions = positions;
        this.occupations = occupations;
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
     * @param userId   пользователь
     * @param language язык названий групп
     * @return активная версия условий; пусто — условия не заданы
     */
    @Transactional(readOnly = true)
    public Optional<Saved> current(long userId, String language) {
        return conditions.findByUserIdAndActiveTrue(userId).map(condition -> saved(condition, language));
    }

    /**
     * Сохраняет условия одной транзакцией под блокировкой активной версии. Условий нет — первая
     * версия (без {@code If-Match}). Есть — нужна текущая версия: изменились страны, набор позиций и групп или
     * формат — прежняя версия гасится и создаётся новая с пустым накопленным списком; изменился только
     * лимит — меняется в той же версии, список не сбрасывается. Статусы вакансий не затрагиваются.
     *
     * @param userId  пользователь
     * @param request условия
     * @param ifMatch  версия из {@code If-Match}; {@code null} — не указана
     * @param language язык названий групп в ответе
     * @return сохранённые условия
     * @throws InvalidFieldException      неподдерживаемая страна, неизвестная позиция или группа, выключенная позиция,
     *                                    которой нет в текущих условиях ({@code /positions})
     * @throws ConditionVersionException  версия не указана ({@code 428}) или не та ({@code 412})
     */
    @Transactional
    public Saved save(long userId, ConditionRequest request, String ifMatch, String language) {
        List<String> countries = countries(request.countries());
        int limit = request.portionLimit() == null ? properties.defaultPortionLimit() : request.portionLimit();
        Optional<SearchCondition> active = conditions.findActiveForUpdate(userId);
        Selection selection = selection(request.positions(),
                active.map(condition -> condition.selection().positionIds()).orElse(Set.of()));
        if (active.isEmpty()) {
            if (ifMatch != null) {
                throw new ConditionVersionException(false);
            }
            return saved(conditions.save(new SearchCondition(userId, countries, selection, request.format(), limit)),
                    language);
        }
        SearchCondition current = active.get();
        if (ifMatch == null) {
            throw new ConditionVersionException(true);
        }
        if (!ifMatch.equals(current.etag())) {
            throw new ConditionVersionException(false);
        }
        if (current.sameSelection(countries, selection, request.format())) {
            current.setPortionLimit(limit);
            return saved(conditions.saveAndFlush(current), language);
        }
        current.deactivate();
        conditions.saveAndFlush(current);
        return saved(conditions.save(new SearchCondition(userId, countries, selection, request.format(), limit)),
                language);
    }

    /**
     * Позиции и группы по кодам (без повторов): 4 цифры — группа ISCO-08 справочника (§204), иначе — позиция словаря,
     * включённая, кроме уже выбранных в текущих условиях: выключенная позиция остаётся у тех, кто её выбрал, но заново
     * не выбирается (§160).
     */
    private Selection selection(List<String> codes, Set<Long> kept) {
        Map<Boolean, Set<String>> requested = codes.stream().map(String::strip)
                .collect(Collectors.partitioningBy(code -> GROUP.matcher(code).matches(), Collectors.toSet()));
        Set<String> groups = requested.get(true);
        List<Position> found = positions.findByCodeIn(requested.get(false));
        Set<String> known = new HashSet<>(occupations.knownGroups(groups));
        found.forEach(position -> known.add(position.getCode()));
        if (known.size() != groups.size() + requested.get(false).size()) {
            throw new InvalidFieldException("/positions", "Unknown positions: " + codes.stream().map(String::strip)
                    .filter(code -> !known.contains(code)).distinct().sorted().toList());
        }
        List<String> retired = found.stream().filter(position -> !position.isActive() && !kept.contains(position.getId()))
                .map(Position::getCode).sorted().toList();
        if (!retired.isEmpty()) {
            throw new InvalidFieldException("/positions", "Retired positions: " + retired);
        }
        return new Selection(found.stream().map(Position::getId).collect(Collectors.toSet()), groups);
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

    private Saved saved(SearchCondition condition, String language) {
        Selection selection = condition.selection();
        List<ChosenPosition> chosen = new ArrayList<>();
        positions.findAllById(selection.positionIds()).forEach(position -> chosen.add(
                new ChosenPosition(position.getCode(), position.getName(), position.isActive())));
        occupations.groupNames(selection.groups(), language)
                .forEach((code, name) -> chosen.add(new ChosenPosition(code, name, true)));
        chosen.sort(Comparator.comparing(ChosenPosition::code));
        return new Saved(new ConditionResponse(condition.getCountries(), chosen, condition.getWorkFormat(),
                condition.getPortionLimit()), condition.etag());
    }
}
