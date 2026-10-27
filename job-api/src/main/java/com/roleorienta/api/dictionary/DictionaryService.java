package com.roleorienta.api.dictionary;

import com.roleorienta.api.condition.InvalidFieldException;
import com.roleorienta.api.dictionary.DictionaryDtos.ChosenWithoutVacancies;
import com.roleorienta.api.dictionary.DictionaryDtos.DictionaryView;
import com.roleorienta.api.dictionary.DictionaryDtos.NewPosition;
import com.roleorienta.api.dictionary.DictionaryDtos.PositionChange;
import com.roleorienta.api.dictionary.DictionaryDtos.SearchMiss;
import com.roleorienta.api.dictionary.DictionaryDtos.Settings;
import com.roleorienta.api.dictionary.DictionaryDtos.Terms;
import com.roleorienta.api.dictionary.DictionaryRepository.Versioned;
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

    private final DictionaryRepository repository;

    /**
     * @param repository словарь в БД
     */
    public DictionaryService(DictionaryRepository repository) {
        this.repository = repository;
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
