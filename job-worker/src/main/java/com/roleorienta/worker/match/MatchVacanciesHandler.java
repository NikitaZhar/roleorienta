package com.roleorienta.worker.match;

import com.roleorienta.worker.match.MatchRepository.StoredDictionary;
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
 * стране и формате работы и требований к языку (технический документ §6 «Соответствие», «Нормализация»; подэтап
 * 1.4; §158). Словарь позиций читается из БД в начале каждого задания (§159). Версия сопоставления — версия словаря
 * позиций ({@code d<номер>}), версия регионов, размер справочника городов, версия правил
 * распознавания мест ({@code LocationResolver.VERSION}) и слов языков ({@code languages.json}): любая смена
 * пересчитывает все вакансии. Страна не
 * определилась по местам, а вакансия пришла с государственного портала — страна портала (§149). Берутся незакрытые вакансии, не сопоставленные по текущей
 * версии; соответствия и сведения вакансии заменяются вместе с отметкой версии. За задание — {@value #BATCH} вакансий, дальше —
 * следующее задание.
 */
@Component
public class MatchVacanciesHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "MATCH_VACANCIES";

    /**
     * Наибольшая длина версии сопоставления: она хранится в {@code vacancy.match_version} и
     * {@code vacancy_position_match.dictionary_version} — {@code VARCHAR(40)} (миграция V16).
     */
    static final int MAX_VERSION_LENGTH = 40;

    private static final String PAYLOAD = "{}";
    private static final int BATCH = 500;
    private static final Logger LOG = LoggerFactory.getLogger(MatchVacanciesHandler.class);

    private final RemoteRegions regions;
    private final LanguageRequirements languages;
    private final MatchRepository repository;
    private final TaskService taskService;

    /**
     * @param regions     регионы удалённой работы
     * @param languages   требования к языку в тексте
     * @param repository  позиции, соответствия, города
     * @param taskService постановка следующего задания
     */
    public MatchVacanciesHandler(RemoteRegions regions, LanguageRequirements languages, MatchRepository repository,
            TaskService taskService) {
        this.regions = regions;
        this.languages = languages;
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
        StoredDictionary stored = repository.dictionary();
        PositionDictionary dictionary = stored.words();
        Map<String, Long> positionIds = stored.positionIds();
        Map<String, String> cities = repository.cities();
        LocationResolver resolver = new LocationResolver(cities, regions);
        String version = version(dictionary.version(), regions.version(), languages.version(), cities.size());
        List<VacancyText> vacancies = repository.vacanciesToMatch(version, BATCH);
        int matched = 0;
        for (VacancyText vacancy : vacancies) {
            String text = text(vacancy.content());
            Map<Long, String> positions = new LinkedHashMap<>();
            PositionMatcher.match(dictionary, vacancy.title(), text)
                    .forEach(match -> positions.put(positionIds.get(match.code()), match.explanation()));
            try {
                repository.saveMatches(vacancy, version, positions,
                        resolver.resolve(vacancy.places().locations(), vacancy.title(), text)
                                .orPortalCountry(vacancy.places().portalCountry()),
                        languages.find(vacancy.content()));
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
     * Версия сопоставления: словарь позиций, регионы, размер справочника городов, правила распознавания мест, слова
     * языков (§158). Длиннее колонок БД — понятная ошибка задания, а не отказ записи на каждой вакансии (стенограмма
     * §149, аудит §154).
     *
     * @param dictionaryVersion версия словаря позиций
     * @param regionsVersion    версия регионов удалённой работы
     * @param languagesVersion  версия слов языков
     * @param cityCount         число названий в справочнике городов
     * @return версия
     * @throws IllegalStateException версия длиннее {@value #MAX_VERSION_LENGTH} символов
     */
    static String version(String dictionaryVersion, String regionsVersion, String languagesVersion, int cityCount) {
        String version = dictionaryVersion + "/" + regionsVersion + "/g" + cityCount + "/" + LocationResolver.VERSION
                + "/" + languagesVersion;
        if (version.length() > MAX_VERSION_LENGTH) {
            throw new IllegalStateException("Match version " + version + " is longer than " + MAX_VERSION_LENGTH
                    + " characters of vacancy.match_version");
        }
        return version;
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
