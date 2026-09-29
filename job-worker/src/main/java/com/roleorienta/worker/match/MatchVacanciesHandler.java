package com.roleorienta.worker.match;

import com.roleorienta.worker.match.MatchRepository.VacancyText;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Задание {@code MATCH_VACANCIES}: предрасчёт соответствия вакансий позициям словаря и сведений о
 * стране и формате работы (технический документ §6 «Соответствие», «Нормализация»; подэтап 1.4).
 * Версия сопоставления — версии словаря позиций и регионов и размер справочника городов: любая
 * смена пересчитывает все вакансии. Берутся незакрытые вакансии, не сопоставленные по текущей
 * версии; соответствия и сведения вакансии заменяются вместе с отметкой версии. За задание — {@value #BATCH} вакансий, дальше —
 * следующее задание.
 */
@Component
public class MatchVacanciesHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "MATCH_VACANCIES";

    private static final String PAYLOAD = "{}";
    private static final int BATCH = 500;
    private static final Logger LOG = LoggerFactory.getLogger(MatchVacanciesHandler.class);

    private final PositionDictionary dictionary;
    private final RemoteRegions regions;
    private final MatchRepository repository;
    private final TaskService taskService;

    /**
     * @param dictionary  словарь позиций
     * @param regions     регионы удалённой работы
     * @param repository  позиции, соответствия, города
     * @param taskService постановка следующего задания
     */
    public MatchVacanciesHandler(PositionDictionary dictionary, RemoteRegions regions, MatchRepository repository,
            TaskService taskService) {
        this.dictionary = dictionary;
        this.regions = regions;
        this.repository = repository;
        this.taskService = taskService;
    }

    /**
     * @param suffix часть ключа: время постановки или место прохода
     * @return ключ задания
     */
    public static String taskKey(String suffix) {
        return "match:" + suffix;
    }

    /**
     * @return параметры задания (не нужны)
     */
    public static String payload() {
        return PAYLOAD;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        Map<String, Long> positionIds = repository.syncPositions(dictionary);
        Map<String, String> cities = repository.cities();
        LocationResolver resolver = new LocationResolver(cities, regions);
        String version = dictionary.version() + "/" + regions.version() + "/g" + cities.size();
        List<VacancyText> vacancies = repository.vacanciesToMatch(version, BATCH);
        int matched = 0;
        for (VacancyText vacancy : vacancies) {
            String text = text(vacancy.content());
            Map<Long, String> positions = new LinkedHashMap<>();
            PositionMatcher.match(dictionary, vacancy.title(), text)
                    .forEach(match -> positions.put(positionIds.get(match.code()), match.explanation()));
            try {
                repository.saveMatches(vacancy, version, positions,
                        resolver.resolve(vacancy.locations(), vacancy.title(), text));
                matched += positions.isEmpty() ? 0 : 1;
            } catch (OptimisticLockingFailureException changed) {
                LOG.debug("Vacancy {} changed while matching, next pass", vacancy.id());
            }
        }
        LOG.info("Matched {} vacancies by {}: {} with a position", vacancies.size(), version, matched);
        if (vacancies.size() == BATCH) {
            taskService.enqueue(TYPE, taskKey(version + ":" + vacancies.get(BATCH - 1).id()), PAYLOAD);
        }
        return new TaskOutcome.Done();
    }

    /**
     * Текст без разметки; разметка, закодированная сущностями (так отдаёт Greenhouse), снимается
     * повторным разбором.
     */
    private static String text(String html) {
        String text = Jsoup.parse(html).text();
        return text.contains("<") ? Jsoup.parse(text).text() : text;
    }
}
