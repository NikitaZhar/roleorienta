package com.roleorienta.worker.career;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.jobposting.JobPostingAdapter;
import com.roleorienta.worker.career.CareerScanRepository.CheckResult;
import com.roleorienta.worker.career.CareerScanRepository.Role;
import com.roleorienta.worker.career.CareerScanRepository.Site;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.site.SiteBrand;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Задание {@code CAREER_SCAN}: сайты компаний → кадровые страницы → источники (технический документ
 * §5.1, подэтап 1.7). Страницы сайтов читаются {@link ExternalHttpClient} (robots.txt, бюджет на
 * хост, защита от SSRF).
 *
 * <ol>
 *   <li>Главная страница: ссылки на доски Workday, Greenhouse, Personio ({@link CareerLinks}).</li>
 *   <li>Досок нет — кадровая страница сайта, как в скрипте замера топ-500 (§76): ссылка с главной с кадровым словом
 *       ({@link CareerLinks}); страница засчитывается, только если в её тексте есть кадровое слово (пробный адрес —
 *       и если переадресация не привела на главную).
 *       Нет ссылки, ссылка не кадровая или главная не открылась — пробные адреса: {@code /kariera},
 *       {@code /sk/kariera}, {@code /careers}, {@code /kariera-a-praca}, {@code /pre-uchadzacov},
 *       {@code kariera.|jobs.|careers.<домен>}, {@code jobs.|careers.<бренд>.com}, {@code www.<бренд>-jobs.sk}. На
 *       кадровой странице — те же ссылки на доски; нет и их — страница с разметкой {@code JobPosting} (она сама или
 *       одна из первых {@value #VACANCY_CHECKS} ссылок вглубь) подключается как источник {@code jobposting}; нет и
 *       её — один шаг вглубь по кадровой ссылке ({@code /kariera} → {@code /kariera/volne-pozicie}).</li>
 *   <li>Найденное подключается как источник и связывается с компанией: доска, на которую ссылается
 *       подтверждённый по IČO сайт компании, принадлежит ей (гейт принадлежности, бизнес-описание
 *       §4.2). Итог — с причиной; перепроверка через {@link CareerProperties#recheckAfter()}.</li>
 *   <li>Основание использования (бизнес-описание §10, {@code source_permission}): подключается только
 *       источник провайдера с разрешением; у кадрового агентства — ещё и с разрешением на это агентство,
 *       связь — с ролью «размещающее агентство». Нет разрешения или главная запрещена robots.txt —
 *       итог «использование запрещено».</li>
 *   <li>Главная не открылась (401/403 — ограничение не обходится; таймаут, 5xx, нет домена): кадровая страница
 *       ищется по пробным адресам (при таймауте, 5xx и несуществующем домене — только кадровые хосты, не пути на том
 *       же хосте); не открылось ничего — итог «сайт не ответил» (у компании — «источник недоступен»).</li>
 * </ol>
 *
 * <p>За задание — {@link CareerProperties#sitesPerTask()} сайтов, проверяемых одновременно (не больше
 * {@code app.career.parallel-sites}, §77), дальше — следующее задание.
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
    private static final List<String> CAREER_PATHS = List.of("/kariera", "/sk/kariera", "/careers", "/kariera-a-praca",
            "/pre-uchadzacov");
    /** Пробные кадровые поддомены домена сайта. */
    private static final List<String> CAREER_SUBDOMAINS = List.of("kariera", "jobs", "careers");
    /** Пробные кадровые хосты бренда сайта ({@code %s} — первое слово регистрируемого домена), как в скрипте замера. */
    private static final List<String> BRAND_CAREER_HOSTS = List.of("jobs.%s.com", "careers.%s.com", "www.%s-jobs.sk");
    private static final Pattern IP_ADDRESS = Pattern.compile("[0-9.]+|\\[.*]");
    private static final Logger LOG = LoggerFactory.getLogger(CareerScanHandler.class);

    private final ExternalHttpClient http;
    private final CareerScanRepository repository;
    private final TaskService taskService;
    private final CareerProperties properties;
    private final int parallelSites;

    /**
     * @param http          внешний HTTP-клиент
     * @param repository    сайты и источники
     * @param taskService   постановка следующего задания
     * @param properties    схема, сайтов за задание, срок перепроверки
     * @param parallelSites сколько сайтов задания проверяется одновременно ({@code app.career.parallel-sites})
     */
    public CareerScanHandler(ExternalHttpClient http, CareerScanRepository repository, TaskService taskService,
            CareerProperties properties, @Value("${app.career.parallel-sites:6}") int parallelSites) {
        this.http = http;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
        this.parallelSites = parallelSites;
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
        checkInParallel(sites, providers);
        if (sites.size() == properties.sitesPerTask()) {
            taskService.enqueue(TYPE, continuationKey(day, sites.get(sites.size() - 1).id()), payload(day));
        }
        return new TaskOutcome.Done();
    }

    /**
     * Сайты задания проверяются одновременно, не больше {@code parallelSites} (§77): почти всё время проверки — ожидание
     * ответов сайтов, поэтому по одному задание не укладывалось в аренду. Вежливость к хостам не меняется — промежуток
     * между запросами к одному хосту держит общий бюджет в БД ({@code app.http.politeness.host-interval}). Сайты задания
     * принадлежат разным компаниям (у компании проверяется один основной сайт), итог каждого пишется своей транзакцией.
     * Ошибка проверки одного сайта, как и прежде, завершает задание ошибкой (повтор — таблица заданий); остальные
     * сайты к этому моменту уже проверены и записаны.
     */
    private void checkInParallel(List<Site> sites, Set<String> providers) {
        List<Callable<Void>> checks = sites.stream().map(site -> (Callable<Void>) () -> {
            check(site, providers);
            return null;
        }).toList();
        int threads = Math.max(1, Math.min(parallelSites, sites.size()));
        try (ExecutorService pool = Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name("career-scan-", 0).factory())) {
            for (Future<Void> done : pool.invokeAll(checks)) {
                done.get();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CAREER_SCAN interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("CAREER_SCAN site check failed", failed.getCause());
        }
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
        if (homeResult instanceof HttpResult.PermanentFailure failure
                && failure.kind() == HttpResult.Kind.USE_FORBIDDEN) {
            repository.record(site, Set.of(), CheckResult.USE_FORBIDDEN, null, Role.EMPLOYER);
            return;
        }
        Optional<Document> homePage = homeResult instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(home).toString())) : Optional.empty();
        Set<Board> boards = homePage.map(CareerLinks::boards).orElse(Set.of());
        Optional<String> careerLink = homePage.flatMap(page -> CareerLinks.careerPage(page,
                URI.create(page.location()).getAuthority()));
        Optional<Document> careerPage = Optional.empty();
        if (boards.isEmpty()) {
            careerPage = careerLink.flatMap(link -> page(URI.create(link))).filter(CareerLinks::isCareerPage);
            if (careerPage.isEmpty()) {
                careerPage = standardCareerPage(home, hostAnswers(homeResult));
            }
            boards = careerPage.map(page -> careerBoards(page, true)).orElse(Set.of());
        }
        if (homePage.isEmpty() && careerPage.isEmpty()) {
            LOG.info("Home page of site {} of company {} did not open ({}), no career address open", site.host(),
                    site.companyId(), homeResult);
            repository.record(site, Set.of(), CheckResult.UNREACHABLE, null, Role.EMPLOYER);
            return;
        }
        Optional<String> careerUrl = careerPage.isPresent() ? careerPage.map(Document::location) : careerLink;
        Set<Board> permitted = boards.stream().filter(board -> providers.contains(board.provider()))
                .collect(Collectors.toSet());
        Optional<Role> role = repository.permittedRole(site.companyId());
        CheckResult result;
        if (!boards.isEmpty() && (permitted.isEmpty() || role.isEmpty())) {
            result = CheckResult.USE_FORBIDDEN;
            permitted = Set.of();
        } else {
            result = !boards.isEmpty() ? CheckResult.SOURCE_FOUND
                    : careerPage.isPresent() ? CheckResult.FORMAT_UNSUPPORTED : CheckResult.NO_CAREER_PAGE;
        }
        repository.record(site, permitted, result, careerUrl.orElse(null), role.orElse(Role.EMPLOYER));
        LOG.info("Site {} of company {}: {} {}", site.host(), site.companyId(), result, boards);
    }

    /**
     * Хост сайта отвечает: главная открылась, закрыта для программы (401/403) или ответила постоянной ошибкой страницы.
     * Нет — при таймауте, обрыве, 5xx и несуществующем домене: пробные пути на том же хосте не запрашиваются (задание
     * укладывается в аренду), пробуются только кадровые хосты.
     */
    private static boolean hostAnswers(HttpResult homeResult) {
        return !(homeResult instanceof HttpResult.TemporaryFailure)
                && !(homeResult instanceof HttpResult.PermanentFailure failure
                        && (failure.kind() == HttpResult.Kind.NO_SUCH_HOST || failure.kind() == HttpResult.Kind.BLOCKED));
    }

    /**
     * Первый пробный кадровый адрес, который открылся и прошёл проверку {@link #careerPage(URI, URI)}: пути на сайте
     * (если хост отвечает), кадровые поддомены домена сайта, кадровые хосты бренда — как в скрипте замера
     * ({@code career_candidates} в {@code survey/employer-survey.py}). Скрипт пробует их после любой неудачи главной:
     * нет ссылки, ссылка не кадровая, главная не открылась.
     *
     * @param home        главная (стартовая страница) сайта
     * @param hostAnswers хост сайта отвечает — пробовать пути на нём
     * @return кадровая страница; пусто — ни один адрес не подошёл
     */
    private Optional<Document> standardCareerPage(URI home, boolean hostAnswers) {
        List<URI> candidates = new ArrayList<>();
        if (hostAnswers) {
            CAREER_PATHS.stream().map(home::resolve).forEach(candidates::add);
        }
        String domain = home.getHost().startsWith("www.") ? home.getHost().substring(4) : home.getHost();
        if (!IP_ADDRESS.matcher(domain).matches()) {
            CAREER_SUBDOMAINS.forEach(sub -> candidates.add(
                    URI.create(home.getScheme() + "://" + sub + "." + domain + "/")));
            String brand = SiteBrand.registrable(domain).split("\\.")[0];
            BRAND_CAREER_HOSTS.forEach(pattern -> candidates.add(URI.create("https://" + pattern.formatted(brand) + "/")));
        }
        for (URI candidate : candidates) {
            Optional<Document> page = careerPage(candidate, home);
            if (page.isPresent()) {
                return page;
            }
        }
        return Optional.empty();
    }

    /**
     * Кадровая страница по пробному адресу: открылась, в тексте есть кадровое слово ({@link CareerLinks#isCareerPage})
     * и переадресация не привела на главную сайта (сайт отвечает главной на любой адрес — её меню со словом «Kariéra»
     * не делает её кадровой страницей). Для ссылки с главной это условие не ставится: ссылка на главную или её раздел
     * ({@code kariera.xxxlutz.sk/}, {@code /#career}) — сайт сам кадровый (прогон эталона §76).
     */
    private Optional<Document> careerPage(URI address, URI home) {
        return page(address).filter(CareerLinks::isCareerPage).filter(page -> !isHome(URI.create(page.location()), home));
    }

    private static boolean isHome(URI address, URI home) {
        String path = address.getPath() == null ? "" : address.getPath().replaceAll("/+$", "");
        return path.isEmpty() && withoutWww(address.getHost()).equals(withoutWww(home.getHost()));
    }

    private static String withoutWww(String host) {
        String lower = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return lower.startsWith("www.") ? lower.substring(4) : lower;
    }

    /**
     * Доски на кадровой странице; нет — сама страница как источник {@code jobposting}, если на ней или
     * на первых страницах вакансий есть разметка {@code JobPosting}; нет и её — один шаг вглубь по
     * кадровой ссылке ({@code deeper}). Хост и путь кадровой страницы — по её конечному адресу после
     * переадресаций (аудит §66).
     */
    private Set<Board> careerBoards(Document careerPage, boolean deeper) {
        Set<Board> boards = CareerLinks.boards(careerPage);
        if (!boards.isEmpty()) {
            return boards;
        }
        URI actual = URI.create(careerPage.location());
        if (actual.toString().length() <= MAX_BOARD && (CareerLinks.hasJobPosting(careerPage) || CareerLinks
                .vacancyLinks(careerPage, actual, VACANCY_CHECKS).stream()
                .map(link -> page(URI.create(link)))
                .anyMatch(vacancy -> vacancy.isPresent() && CareerLinks.hasJobPosting(vacancy.get())))) {
            return Set.of(new Board(JobPostingAdapter.PROVIDER, actual.toString()));
        }
        Optional<String> next = deeper ? CareerLinks.deeperCareerPage(careerPage, actual) : Optional.empty();
        return next.flatMap(link -> page(URI.create(link))).map(page -> careerBoards(page, false)).orElse(Set.of());
    }

    private Optional<Document> page(URI uri) {
        HttpResult result = http.get(uri);
        return result instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(uri).toString())) : Optional.empty();
    }
}
