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
 * Задание {@code MATCH_VACANCIES}: предрасчёт соответствия вакансий позициям словаря (технический
 * документ §6 «Соответствие»; подэтап 1.4). Берутся незакрытые вакансии, не сопоставленные по
 * текущей версии словаря (новые, изменившиеся или сопоставленные прежней версией); соответствия
 * вакансии заменяются вместе с отметкой версии. За задание — {@value #BATCH} вакансий, дальше —
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
    private final MatchRepository repository;
    private final TaskService taskService;

    /**
     * @param dictionary  словарь позиций
     * @param repository  позиции и соответствия
     * @param taskService постановка следующего задания
     */
    public MatchVacanciesHandler(PositionDictionary dictionary, MatchRepository repository,
            TaskService taskService) {
        this.dictionary = dictionary;
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
        List<VacancyText> vacancies = repository.vacanciesToMatch(dictionary.version(), BATCH);
        int matched = 0;
        for (VacancyText vacancy : vacancies) {
            Map<Long, String> positions = new LinkedHashMap<>();
            PositionMatcher.match(dictionary, vacancy.title(), text(vacancy.content()))
                    .forEach(match -> positions.put(positionIds.get(match.code()), match.explanation()));
            try {
                repository.saveMatches(vacancy, dictionary.version(), positions);
                matched += positions.isEmpty() ? 0 : 1;
            } catch (OptimisticLockingFailureException changed) {
                LOG.debug("Vacancy {} changed while matching, next pass", vacancy.id());
            }
        }
        LOG.info("Matched {} vacancies by dictionary {}: {} with a position", vacancies.size(),
                dictionary.version(), matched);
        if (vacancies.size() == BATCH) {
            taskService.enqueue(TYPE, taskKey(dictionary.version() + ":" + vacancies.get(BATCH - 1).id()), PAYLOAD);
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
