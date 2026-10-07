package com.roleorienta.worker.career;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.jobposting.JobPostingAdapter;
import com.roleorienta.worker.career.CareerScanRepository.CheckResult;
import com.roleorienta.worker.career.CareerScanRepository.Role;
import com.roleorienta.worker.career.CareerScanRepository.Site;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code CAREER_SCAN}: сайты компаний → кадровые страницы → источники (технический документ
 * §5.1, подэтап 1.7). Страницы сайтов читаются {@link ExternalHttpClient} (robots.txt, бюджет на
 * хост, защита от SSRF).
 *
 * <ol>
 *   <li>Главная страница: ссылки на доски Workday, Greenhouse, Personio ({@link CareerLinks}).</li>
 *   <li>Досок нет — кадровая страница сайта («kariéra», «práca», «jobs» …; ссылка на тот же сайт или
 *       его поддомен, а без ссылки — пробный адрес {@code /kariera}, {@code /sk/kariera}, {@code /careers},
 *       {@code /pre-uchadzacov}, {@code kariera.|jobs.|careers.<домен>} с кадровым словом в заголовке): на
 *       ней те же ссылки; нет и их — страница с
 *       разметкой {@code JobPosting} (она сама или одна из первых {@value #VACANCY_CHECKS} ссылок вглубь)
 *       подключается как источник {@code jobposting}; нет и её — один шаг вглубь по кадровой ссылке
 *       ({@code /kariera} → {@code /kariera/volne-pozicie}).</li>
 *   <li>Найденное подключается как источник и связывается с компанией: доска, на которую ссылается
 *       подтверждённый по IČO сайт компании, принадлежит ей (гейт принадлежности, бизнес-описание
 *       §4.2). Итог — с причиной; перепроверка через {@link CareerProperties#recheckAfter()}.</li>
 *   <li>Основание использования (бизнес-описание §10, {@code source_permission}): подключается только
 *       источник провайдера с разрешением; у кадрового агентства — ещё и с разрешением на это агентство,
 *       связь — с ролью «размещающее агентство». Нет разрешения или главная запрещена robots.txt —
 *       итог «использование запрещено».</li>
 *   <li>Главная закрыта для программы (401/403; ограничение не обходится): кадровая страница ищется по
 *       пробным адресам (кадровый поддомен обычно открыт); не открылось ничего — итог «сайт не ответил»
 *       (у компании — «источник недоступен»).</li>
 * </ol>
 *
 * <p>За задание — {@link CareerProperties#sitesPerTask()} сайтов, дальше — следующее задание.
 * Подключённые источники читает общий сбор (раз в сутки).</p>
 */
@Component
public class CareerScanHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "CAREER_SCAN";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VACANCY_CHECKS = 3;
    /** Длина {@code source.board} в схеме. */
    private static final int MAX_BOARD = 200;
    /** Пробные адреса кадровой страницы на сайте, когда на главной нет ссылки или главная закрыта. */
    private static final List<String> CAREER_PATHS = List.of("/kariera", "/sk/kariera", "/careers", "/pre-uchadzacov");
    /** Пробные кадровые поддомены домена сайта. */
    private static final List<String> CAREER_SUBDOMAINS = List.of("kariera", "jobs", "careers");
    private static final Pattern IP_ADDRESS = Pattern.compile("[0-9.]+|\\[.*]");
    private static final Logger LOG = LoggerFactory.getLogger(CareerScanHandler.class);

    private final ExternalHttpClient http;
    private final CareerScanRepository repository;
    private final TaskService taskService;
    private final CareerProperties properties;

    /**
     * @param http        внешний HTTP-клиент
     * @param repository  сайты и источники
     * @param taskService постановка следующего задания
     * @param properties  схема, сайтов за задание, срок перепроверки
     */
    public CareerScanHandler(ExternalHttpClient http, CareerScanRepository repository, TaskService taskService,
            CareerProperties properties) {
        this.http = http;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * @param suffix часть ключа: дата постановки или место проверки
     * @return ключ задания
     */
    public static String taskKey(String suffix) {
        return "career-scan:" + suffix;
    }

    /**
     * @param day день постановки цепочки — входит в ключи её продолжений
     * @return параметры задания
     */
    public static String payload(String day) {
        return JSON.createObjectNode().put("day", day).toString();
    }

    /**
     * Ключ продолжения цепочки: день постановки и последний проверенный сайт. Без дня ключ совпадал бы с ключом
     * прошлых дней (сайты перепроверяются через срок, задания хранятся, ключ уникален) — продолжение не ставилось,
     * и цепочка дня обрывалась (стенограмма §57).
     *
     * @param day    день постановки цепочки
     * @param siteId последний проверенный сайт
     * @return ключ задания
     */
    static String continuationKey(String day, long siteId) {
        return taskKey(day + ":site-" + siteId);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        String day = day(task.payload());
        List<Site> sites = repository.nextSites(properties.sitesPerTask(), properties.recheckAfter());
        Set<String> providers = repository.permittedProviders();
        for (Site site : sites) {
            check(site, providers);
        }
        if (sites.size() == properties.sitesPerTask()) {
            taskService.enqueue(TYPE, continuationKey(day, sites.get(sites.size() - 1).id()), payload(day));
        }
        return new TaskOutcome.Done();
    }

    private static String day(String payload) {
        try {
            return JSON.readTree(payload).path("day").asText("");
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed CAREER_SCAN payload: " + payload, exception);
        }
    }

    private void check(Site site, Set<String> providers) {
        URI home = URI.create(site.startUrl() != null ? site.startUrl()
                : properties.scheme() + "://" + site.host() + "/");
        HttpResult homeResult = http.get(home);
        boolean closed = homeResult instanceof HttpResult.PermanentFailure failure
                && failure.kind() == HttpResult.Kind.ACCESS_DENIED;
        if (!(homeResult instanceof HttpResult.Success) && !closed) {
            boolean forbidden = homeResult instanceof HttpResult.PermanentFailure failure
                    && failure.kind() == HttpResult.Kind.USE_FORBIDDEN;
            repository.record(site, Set.of(), forbidden ? CheckResult.USE_FORBIDDEN : CheckResult.UNREACHABLE,
                    null, Role.EMPLOYER);
            return;
        }
        Optional<Document> homePage = homeResult instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(home).toString())) : Optional.empty();
        Set<Board> boards = homePage.map(CareerLinks::boards).orElse(Set.of());
        Optional<String> careerUrl = homePage.flatMap(page -> CareerLinks.careerPage(page,
                URI.create(page.location()).getAuthority()));
        if (boards.isEmpty() && careerUrl.isEmpty()) {
            careerUrl = standardCareerPage(home);
        }
        if (closed && careerUrl.isEmpty()) {
            LOG.info("Site {} of company {} is closed for the program, no career address open", site.host(),
                    site.companyId());
            repository.record(site, Set.of(), CheckResult.UNREACHABLE, null, Role.EMPLOYER);
            return;
        }
        if (boards.isEmpty() && careerUrl.isPresent()) {
            boards = careerBoards(URI.create(careerUrl.get()), true);
        }
        Set<Board> permitted = boards.stream().filter(board -> providers.contains(board.provider()))
                .collect(Collectors.toSet());
        Optional<Role> role = repository.permittedRole(site.companyId());
        CheckResult result;
        if (!boards.isEmpty() && (permitted.isEmpty() || role.isEmpty())) {
            result = CheckResult.USE_FORBIDDEN;
            permitted = Set.of();
        } else {
            result = !boards.isEmpty() ? CheckResult.SOURCE_FOUND
                    : careerUrl.isPresent() ? CheckResult.FORMAT_UNSUPPORTED : CheckResult.NO_CAREER_PAGE;
        }
        repository.record(site, permitted, result, careerUrl.orElse(null), role.orElse(Role.EMPLOYER));
        LOG.info("Site {} of company {}: {} {}", site.host(), site.companyId(), result, boards);
    }

    /**
     * Первый пробный кадровый адрес (путь на сайте, затем кадровый поддомен его домена), который открылся
     * и в заголовке которого есть кадровое слово; нет — пусто.
     */
    private Optional<String> standardCareerPage(URI home) {
        List<URI> candidates = new ArrayList<>(CAREER_PATHS.stream().map(home::resolve).toList());
        String domain = home.getHost().startsWith("www.") ? home.getHost().substring(4) : home.getHost();
        if (!IP_ADDRESS.matcher(domain).matches()) {
            CAREER_SUBDOMAINS.forEach(sub -> candidates.add(
                    URI.create(home.getScheme() + "://" + sub + "." + domain + "/")));
        }
        for (URI candidate : candidates) {
            Optional<Document> page = page(candidate);
            if (page.isPresent() && CareerLinks.isCareerPage(page.get())) {
                return Optional.of(candidate.toString());
            }
        }
        return Optional.empty();
    }

    /**
     * Доски на кадровой странице; нет — сама страница как источник {@code jobposting}, если на ней или
     * на первых страницах вакансий есть разметка {@code JobPosting}; нет и её — один шаг вглубь по
     * кадровой ссылке ({@code deeper}). Хост и путь кадровой страницы — по её конечному адресу после
     * переадресаций (аудит §66).
     */
    private Set<Board> careerBoards(URI careerUrl, boolean deeper) {
        Optional<Document> careerPage = page(careerUrl);
        if (careerPage.isEmpty()) {
            return Set.of();
        }
        Set<Board> boards = CareerLinks.boards(careerPage.get());
        if (!boards.isEmpty()) {
            return boards;
        }
        URI actual = URI.create(careerPage.get().location());
        if (actual.toString().length() <= MAX_BOARD && (CareerLinks.hasJobPosting(careerPage.get()) || CareerLinks
                .vacancyLinks(careerPage.get(), actual, VACANCY_CHECKS).stream()
                .map(link -> page(URI.create(link)))
                .anyMatch(vacancy -> vacancy.isPresent() && CareerLinks.hasJobPosting(vacancy.get())))) {
            return Set.of(new Board(JobPostingAdapter.PROVIDER, actual.toString()));
        }
        Optional<String> next = deeper ? CareerLinks.deeperCareerPage(careerPage.get(), actual) : Optional.empty();
        return next.isPresent() ? careerBoards(URI.create(next.get()), false) : Set.of();
    }

    private Optional<Document> page(URI uri) {
        HttpResult result = http.get(uri);
        return result instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(uri).toString())) : Optional.empty();
    }
}
