package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.StatePortalProperties;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.stateportal.ProfesiaPages.Found;
import com.roleorienta.worker.stateportal.ProfesiaPages.Lookup;
import com.roleorienta.worker.stateportal.ProfesiaPages.Unavailable;
import com.roleorienta.worker.stateportal.StatePortalRepository.ListedEmployer;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * однозначно). Следующие круги — до {@value #EMPLOYERS_PER_TASK} сопоставленных работодателей: объявление → страница
 * компании и разделы → кадровая ссылка или сайт ({@link ProfesiaPages#find}), запись сайта компании (источник
 * и подтверждение {@code PROFESIA}). Компании, у которых сайт со страницы profesia.sk уже есть (шаг 2 или этот),
 * не берутся. profesia.sk — только путь к сайту: вакансии с неё не читаются.
 *
 * <p>Срок задания, временный отказ profesia.sk и отметка «смотрели» — как у {@link ProfesiaSiteHandler} (аудит §84).
 * Карта сайта: временный отказ — повтор задания, постоянный (запрет robots.txt, 403, больше потолка) — неудача без
 * повторов; на следующий день — снова.</p>
 */
@Component
public class ProfesiaListHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PROFESIA_LIST";

    private static final int EMPLOYERS_PER_TASK = 20;
    /**
     * С этой попытки задания работодатель, на котором profesia.sk отвечает временным отказом, отмечается проверенным
     * без ссылки (аудит §84): постоянно «временно» сломанная страница одного работодателя не держит цепочку; при сбое
     * всей profesia.sk так отмечаются не больше одного работодателя на попытку.
     */
    private static final int SKIP_EMPLOYER_AFTER_ATTEMPTS = 3;
    /** Длины колонок {@code profesia_employer.slug} и {@code ad_url} (V43); длиннее — запись пропускается. */
    private static final int MAX_SLUG = 200;
    private static final int MAX_AD_URL = 1000;
    /** Объявление в карте сайта: {@code <loc>…/praca/<работодатель>/O<номер></loc>}. */
    private static final Pattern OFFER =
            Pattern.compile("<loc>\\s*(https?://[^<\\s]*/praca/([^/<\\s]+)/O\\d+)\\s*</loc>");
    /**
     * Правовая форма в конце названия — то же выражение, что в вычисляемом столбце {@code company.name_slug} (V43);
     * меняются только вместе (применённая миграция не меняется — новой миграцией).
     */
    private static final Pattern LEGAL_FORM = Pattern.compile("(-(spol-s-r-o|s-r-o|sro|a-s|as|k-s|v-o-s|s-e|se|j-s-a"
            + "|v-d|druzstvo|o-z|organizacna-zlozka|odstepny-zavod|gmbh|ag|ltd|inc|kft|sp-z-o-o))+$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ProfesiaListHandler.class);

    private final StatePortalRepository repository;
    private final ProfesiaPages pages;
    private final TaskService taskService;
    private final StatePortalProperties properties;
    private final Duration timeBudget;

    /**
     * @param repository  список работодателей и запись сайта
     * @param pages       чтение profesia.sk: карта сайта, объявление → страница компании → ссылка
     * @param taskService постановка следующего задания цепочки
     * @param properties  срок повторной проверки
     * @param timeBudget  срок задания: после него новые работодатели не начинаются
     */
    public ProfesiaListHandler(StatePortalRepository repository, ProfesiaPages pages,
            TaskService taskService, StatePortalProperties properties,
            @Value("${app.state-portal.profesia-task-time-budget:3m}") Duration timeBudget) {
        this.repository = repository;
        this.pages = pages;
        this.taskService = taskService;
        this.properties = properties;
        this.timeBudget = timeBudget;
    }

    /**
     * Ставит первое задание цепочки (чтение карты сайта).
     *
     * @param day часть ключа: день
     * @return поставлено ли (цепочка идёт или повтор в тот же день — ничего не добавляется; стенограмма §95)
     */
    public boolean enqueue(String day) {
        return taskService.startChain(TYPE, key(day, 0), payload(day, 0));
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
        return round == 0 ? readSitemap(day) : findSites(day, round, task.attempts());
    }

    private TaskOutcome readSitemap(String day) {
        return switch (pages.sitemap()) {
            case HttpResult.Success success -> saveList(day, success.body());
            case HttpResult.TemporaryFailure temporary ->
                    new TaskOutcome.Retry("profesia.sk sitemap not read: " + temporary.reason(),
                            temporary.retryAfter());
            case HttpResult.PermanentFailure permanent ->
                    new TaskOutcome.Failed("profesia.sk sitemap refused: " + permanent.kind() + " "
                            + permanent.reason());
        };
    }

    private TaskOutcome saveList(String day, String sitemap) {
        Map<String, String> employers = employers(sitemap);
        repository.saveProfesiaList(employers);
        LOG.info("Profesia sitemap: {} employers listed", employers.size());
        taskService.enqueue(TYPE, key(day, 1), payload(day, 1));
        return new TaskOutcome.Done();
    }

    private TaskOutcome findSites(String day, int round, int attempts) {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        List<ListedEmployer> due = repository.listedForProfesia(EMPLOYERS_PER_TASK, properties.recheckAfter());
        int done = 0;
        int found = 0;
        for (ListedEmployer employer : due) {
            if (System.nanoTime() - deadline > 0) {
                break;
            }
            Lookup lookup = pages.find(employer.adUrl());
            if (lookup instanceof Unavailable unavailable) {
                if (attempts >= SKIP_EMPLOYER_AFTER_ATTEMPTS) {
                    repository.recordListed(employer, null);
                }
                LOG.warn("Profesia employer list: profesia.sk unavailable after {} employers (attempt {}): {}", done,
                        attempts, unavailable.failure().reason());
                return new TaskOutcome.Retry("profesia.sk unavailable: " + unavailable.failure().reason(),
                        unavailable.failure().retryAfter());
            }
            repository.recordListed(employer, lookup instanceof Found site ? site.site() : null);
            done++;
            found += lookup instanceof Found ? 1 : 0;
        }
        LOG.info("Profesia employer list: {} employers, {} sites or career pages found", done, found);
        if (due.size() == EMPLOYERS_PER_TASK || done < due.size()) {
            taskService.enqueue(TYPE, key(day, round + 1), payload(day, round + 1));
        }
        return new TaskOutcome.Done();
    }

    /**
     * Работодатели карты сайта. Разные работодатели, совпавшие без правовой формы ({@code delta-s-r-o} и
     * {@code delta-a-s} → {@code delta}), не берутся: чьё это объявление — неизвестно (аудит §84). Адреса длиннее
     * колонок таблицы не берутся.
     *
     * @param sitemap карта сайта profesia.sk
     * @return работодатель (адрес без правовой формы в конце) → первое его объявление
     */
    static Map<String, String> employers(String sitemap) {
        Map<String, String> employers = new LinkedHashMap<>();
        Map<String, String> fullNames = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Matcher offer = OFFER.matcher(sitemap);
        while (offer.find()) {
            String slug = slug(offer.group(2));
            String previous = fullNames.putIfAbsent(slug, offer.group(2));
            if (previous != null && !previous.equals(offer.group(2))) {
                ambiguous.add(slug);
            }
            if (slug.length() <= MAX_SLUG && offer.group(1).length() <= MAX_AD_URL) {
                employers.putIfAbsent(slug, offer.group(1));
            }
        }
        employers.keySet().removeAll(ambiguous);
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
