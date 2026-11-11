package com.roleorienta.api.dictionary;

import com.roleorienta.api.condition.ConditionDtos.PositionOption;
import com.roleorienta.api.condition.InvalidFieldException;
import com.roleorienta.api.condition.OccupationRepository;
import com.roleorienta.api.dictionary.DictionaryDtos.ChosenWithoutVacancies;
import com.roleorienta.api.dictionary.DictionaryDtos.DictionaryView;
import com.roleorienta.api.dictionary.DictionaryDtos.GroupHint;
import com.roleorienta.api.dictionary.DictionaryDtos.NewPosition;
import com.roleorienta.api.dictionary.DictionaryDtos.PositionChange;
import com.roleorienta.api.dictionary.DictionaryDtos.ProfesiaPosition;
import com.roleorienta.api.dictionary.DictionaryDtos.SearchMiss;
import com.roleorienta.api.dictionary.DictionaryDtos.Settings;
import com.roleorienta.api.dictionary.DictionaryDtos.Terms;
import com.roleorienta.api.dictionary.DictionaryRepository.Versioned;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Редактирование словаря позиций администратором (бизнес-описание §3; решения владельца 2026-10-21, стенограмма
 * §160). Каждое изменение — одна транзакция под блокировкой строки настроек словаря: изменение записывается и номер
 * версии растёт на 1; воркер на следующем такте сопоставления пересчитывает соответствия всех вакансий. Позиция не
 * удаляется — выключается (на неё ссылаются условия и соответствия); код позиции не меняется. Слова очищаются от
 * пробелов по краям, повторы внутри вида (без учёта регистра) убираются — первое написание остаётся.
 */
@Service
public class DictionaryService {

    /** Сколько позиций profesia.sk без группы показывается администратору (больше вакансий — первыми, §305). */
    static final int PROFESIA_POSITIONS = 30;
    /** Сколько групп в подсказке к позиции profesia.sk. */
    static final int HINTS = 3;
    /** Язык названий групп в подсказке: страница администратора — на английском. */
    private static final String HINT_LANGUAGE = "en";

    private final DictionaryRepository repository;
    private final OccupationRepository occupations;

    /**
     * @param repository  словарь в БД
     * @param occupations подсказка групп ISCO-08 (та же, что у пользователя)
     */
    public DictionaryService(DictionaryRepository repository, OccupationRepository occupations) {
        this.repository = repository;
        this.occupations = occupations;
    }

    /**
     * Настройки и позиции читаются одним снимком данных.
     *
     * @return словарь целиком
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DictionaryView dictionary() {
        Versioned<Settings> settings = repository.settings();
        return new DictionaryView(settings.version(), settings.value().contentHits(),
                settings.value().otherSpecializations(), repository.positions());
    }

    /**
     * @param position новая позиция
     * @return словарь после изменения; пусто — позиция с таким кодом уже есть (ничего не записано)
     * @throws InvalidFieldException группы ISCO-08 нет в справочнике ({@code /group})
     */
    @Transactional
    public Optional<DictionaryView> add(NewPosition position) {
        requireGroup(position.group());
        repository.lockVersion();
        Optional<Long> id = repository.insertPosition(position.code(), position.name().strip(), position.group());
        if (id.isEmpty()) {
            return Optional.empty();
        }
        repository.replaceTerms(id.get(), clean(position.terms()));
        repository.bumpVersion();
        return Optional.of(dictionary());
    }

    /**
     * @param id     позиция
     * @param change название, включена ли, группа, названия по видам
     * @return словарь после изменения; пусто — позиции нет
     * @throws InvalidFieldException группы ISCO-08 нет в справочнике ({@code /group})
     */
    @Transactional
    public Optional<DictionaryView> change(long id, PositionChange change) {
        requireGroup(change.group());
        repository.lockVersion();
        if (!repository.updatePosition(id, change.name().strip(), change.active(), change.group())) {
            return Optional.empty();
        }
        repository.replaceTerms(id, clean(change.terms()));
        repository.bumpVersion();
        return Optional.of(dictionary());
    }

    /**
     * @param settings общие настройки
     * @return словарь после изменения
     */
    @Transactional
    public DictionaryView change(Settings settings) {
        repository.lockVersion();
        repository.saveSettings(new Settings(settings.contentHits(), unique(settings.otherSpecializations())));
        repository.bumpVersion();
        return dictionary();
    }

    /**
     * @return запросы подсказки позиции без результата (§205), чаще — первыми
     */
    @Transactional(readOnly = true)
    public List<SearchMiss> searchMisses() {
        return repository.searchMisses();
    }

    /**
     * @return позиции и группы действующих условий без открытых вакансий (§214)
     */
    @Transactional(readOnly = true)
    public List<ChosenWithoutVacancies> chosenWithoutVacancies() {
        return repository.chosenWithoutVacancies();
    }

    /**
     * Запрос убран из списка (позиция добавлена или не нужна); повторится — появится снова.
     *
     * @param query запрос
     * @return {@code false} — такого нет
     */
    @Transactional
    public boolean forgetSearchMiss(String query) {
        return repository.forgetSearchMiss(query);
    }

    /**
     * Позиции profesia.sk без группы (§305) с подсказкой групп по порядку: позиция словаря с тем же названием
     * ({@link DictionaryRepository#dictionaryGroups}); группы, которые у вакансий с этой позицией уже есть другим путём
     * ({@link DictionaryRepository#neighbourGroups}); если обе пусты — название профессии ESCO
     * ({@link OccupationRepository#suggestions}) по первому варианту названия до «/». Отдельные слова названия не
     * ищутся: «Architect» давал «Building architects» у «IT Architect» (проверка владельца 2026-11-11). Ничего не
     * записывает (запрос администратора — не «искали и не нашли» пользователя).
     *
     * @return не больше {@value #PROFESIA_POSITIONS} позиций, больше вакансий — первыми
     */
    @Transactional(readOnly = true)
    public List<ProfesiaPosition> unmappedProfesiaPositions() {
        return repository.unmappedProfesiaPositions(PROFESIA_POSITIONS).stream()
                .map(position -> new ProfesiaPosition(position.slug(), position.name(), position.vacancies(),
                        hints(position)))
                .toList();
    }

    /**
     * @param slug  ключ позиции profesia.sk
     * @param group группа ISCO-08
     * @return {@code false} — такой позиции нет
     * @throws InvalidFieldException группы нет в справочнике
     */
    @Transactional
    public boolean mapProfesiaPosition(String slug, String group) {
        requireGroup(group);
        return repository.mapProfesiaPosition(slug, group);
    }

    private List<GroupHint> hints(ProfesiaPosition position) {
        String title = position.name().split("/", 2)[0].strip();
        Map<String, GroupHint> found = new LinkedHashMap<>();
        List<GroupHint> candidates = new ArrayList<>(repository.dictionaryGroups(title));
        candidates.addAll(repository.neighbourGroups(position.slug(), HINTS));
        for (GroupHint hint : candidates) {
            found.putIfAbsent(hint.code(), hint);
        }
        if (found.isEmpty() && !title.isEmpty()) {
            occupations.suggestions(title, HINT_LANGUAGE).stream()
                    .filter(PositionOption::group)
                    .forEach(option -> found.putIfAbsent(option.code(),
                            new GroupHint(option.code(), option.name(), "ESCO occupation name")));
        }
        return found.values().stream().limit(HINTS).toList();
    }

    private void requireGroup(String group) {
        if (!repository.isGroup(group)) {
            throw new InvalidFieldException("/group", "Unknown ISCO-08 group: " + group);
        }
    }

    private static Terms clean(Terms terms) {
        return new Terms(unique(terms.titles()), unique(terms.roles()), unique(terms.keywords()),
                unique(terms.exclude()));
    }

    /** Слова без пробелов по краям и без повторов без учёта регистра, по порядку ввода. */
    static List<String> unique(List<String> terms) {
        Map<String, String> unique = new LinkedHashMap<>();
        terms.stream().map(String::strip).forEach(term -> unique.putIfAbsent(term.toLowerCase(Locale.ROOT), term));
        return List.copyOf(unique.values());
    }
}
