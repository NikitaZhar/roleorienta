package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.StatePortalProperties;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.stateportal.StatePortalRepository.FoundSite;
import com.roleorienta.worker.stateportal.StatePortalRepository.ListedEmployer;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Задание {@code PROFESIA_LIST} — шаг «список работодателей profesia.sk» (§83; алгоритм владельца, п. 3: на случай,
 * если вакансии на портале и на profesia.sk различаются). Нулевой круг читает карту сайта profesia.sk
 * ({@code sitemap.php}, объявлена в robots.txt): объявления {@code /praca/<работодатель>/O<номер>} — по первому на
 * работодателя; работодатель сопоставляется компании реестра по названию ({@code company.name_slug}, только
 * однозначно). Следующие круги — по {@value #EMPLOYERS_PER_TASK} сопоставленных работодателей: объявление → страница
 * компании и разделы → кадровая ссылка или сайт ({@link ProfesiaSiteHandler#find}), запись сайта компании (источник
 * и подтверждение {@code PROFESIA}). Компании, у которых сайт со страницы profesia.sk уже есть (шаг 2 или этот),
 * не берутся. profesia.sk — только путь к сайту: вакансии с неё не читаются.
 */
@Component
public class ProfesiaListHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PROFESIA_LIST";

    private static final int EMPLOYERS_PER_TASK = 20;
    /** Объявление в карте сайта: {@code <loc>…/praca/<работодатель>/O<номер></loc>}. */
    private static final Pattern OFFER =
            Pattern.compile("<loc>\\s*(https?://[^<\\s]*/praca/([^/<\\s]+)/O\\d+)\\s*</loc>");
    /** Правовая форма в конце названия — как в {@code company.name_slug} (V43). */
    private static final Pattern LEGAL_FORM = Pattern.compile("(-(spol-s-r-o|s-r-o|sro|a-s|as|k-s|v-o-s|s-e|se|j-s-a"
            + "|v-d|druzstvo|o-z|organizacna-zlozka|odstepny-zavod|gmbh|ag|ltd|inc|kft|sp-z-o-o))+$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ProfesiaListHandler.class);

    private final StatePortalRepository repository;
    private final ProfesiaSiteHandler profesia;
    private final TaskService taskService;
    private final StatePortalProperties properties;
    private final URI sitemap;

    /**
     * @param repository  список работодателей и запись сайта
     * @param profesia    чтение страниц profesia.sk, разбор объявления и страницы компании (шаг 2)
     * @param taskService постановка следующего задания цепочки
     * @param properties  срок повторной проверки
     * @param sitemap     адрес карты сайта profesia.sk
     */
    public ProfesiaListHandler(StatePortalRepository repository, ProfesiaSiteHandler profesia,
            TaskService taskService, StatePortalProperties properties,
            @Value("${app.state-portal.profesia-sitemap-url:https://www.profesia.sk/sitemap.php}") URI sitemap) {
        this.repository = repository;
        this.profesia = profesia;
        this.taskService = taskService;
        this.properties = properties;
        this.sitemap = sitemap;
    }

    /**
     * Ставит первое задание цепочки (чтение карты сайта).
     *
     * @param day часть ключа: день
     * @return поставлено ли (повтор в тот же день ничего не добавляет)
     */
    public boolean enqueue(String day) {
        return taskService.enqueue(TYPE, key(day, 0), payload(day, 0));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        String day = payload.path("day").asText();
        int round = payload.path("round").asInt(0);
        if (round == 0) {
            HttpResult result = profesia.get(sitemap);
            if (!(result instanceof HttpResult.Success success)) {
                return new TaskOutcome.Retry("profesia.sk sitemap not read: " + result, Duration.ZERO);
            }
            Map<String, String> employers = employers(success.body());
            repository.saveProfesiaList(employers);
            LOG.info("Profesia sitemap: {} employers listed", employers.size());
            taskService.enqueue(TYPE, key(day, 1), payload(day, 1));
            return new TaskOutcome.Done();
        }
        List<ListedEmployer> due = repository.listedForProfesia(EMPLOYERS_PER_TASK, properties.recheckAfter());
        int found = 0;
        for (ListedEmployer employer : due) {
            Optional<FoundSite> site = profesia.find(employer.adUrl());
            repository.recordListed(employer, site.orElse(null));
            found += site.isPresent() ? 1 : 0;
        }
        LOG.info("Profesia employer list: {} employers, {} sites or career pages found", due.size(), found);
        if (due.size() == EMPLOYERS_PER_TASK) {
            taskService.enqueue(TYPE, key(day, round + 1), payload(day, round + 1));
        }
        return new TaskOutcome.Done();
    }

    /**
     * @param sitemap карта сайта profesia.sk
     * @return работодатель (адрес без правовой формы в конце) → первое его объявление
     */
    static Map<String, String> employers(String sitemap) {
        Map<String, String> employers = new LinkedHashMap<>();
        Matcher offer = OFFER.matcher(sitemap);
        while (offer.find()) {
            employers.putIfAbsent(slug(offer.group(2)), offer.group(1));
        }
        return employers;
    }

    /**
     * @param employer работодатель в адресе profesia.sk
     * @return он же без правовой формы в конце — для сравнения с {@code company.name_slug}
     */
    static String slug(String employer) {
        return LEGAL_FORM.matcher(employer).replaceFirst("");
    }

    private static String key(String day, int round) {
        return "profesia-list:" + day + ":" + round;
    }

    private static String payload(String day, int round) {
        return JSON.createObjectNode().put("day", day).put("round", round).toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PROFESIA_LIST payload: " + payload, exception);
        }
    }
}
