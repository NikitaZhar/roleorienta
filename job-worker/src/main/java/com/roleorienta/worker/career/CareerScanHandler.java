package com.roleorienta.worker.career;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.careerpage.CareerPageList;
import com.roleorienta.worker.career.CareerScanRepository.CheckResult;
import com.roleorienta.worker.career.CareerScanRepository.Role;
import com.roleorienta.worker.career.CareerScanRepository.Site;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.site.DomainMarketplaces;
import com.roleorienta.worker.site.SiteBrand;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 *       ({@link CareerLinks}); сайт сам кадровый ({@code kariera.sconto.sk}) или стартовый адрес — кадровая страница
 *       ({@code prazdroj.sk/kariera}, ссылка с profesia.sk, §82) и на ней кадровые слова — кадровая страница — она
 *       (§79); страница засчитывается, только если в её тексте есть кадровое слово (пробный адрес —
 *       и если переадресация не привела на главную).
 *       Нет ссылки, ссылка не кадровая или главная не открылась — пробные адреса: {@code /kariera},
 *       {@code /sk/kariera}, {@code /careers}, {@code /kariera-a-praca}, {@code /pre-uchadzacov},
 *       {@code kariera.|jobs.|careers.<домен>}, {@code jobs.|careers.<бренд>.com}, {@code www.<бренд>-jobs.sk}. На
 *       кадровой странице — те же ссылки на доски; нет и их — страница с разметкой {@code JobPosting} (она сама или
 *       одна из первых {@value CareerPages#VACANCY_CHECKS} ссылок вглубь) подключается как источник {@code jobposting}; нет и
 *       её — один шаг вглубь по кадровой ссылке ({@code /kariera} → {@code /kariera/volne-pozicie}); ничего и там —
 *       собственная страница вакансий (HTML-список ссылок на вакансии или фраза «вакансий нет»,
 *       {@link CareerPageList}) подключается как источник {@code careerpage} (§136). Кадровая
 *       страница сайта есть, но читаемого на ней нет — пробуются ещё кадровые хосты ({@code careers.<домен>} …, без
 *       путей на том же хосте): у Swiss Re {@code swissre.com/careers.html} без ссылки на систему найма (поиск —
 *       скриптом), а {@code careers.swissre.com} — SuccessFactors (§89).</li>
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
 * {@code app.career.parallel-sites}, §77), дальше — следующее задание в следующем часу (квота в час — размер задания,
 * §101).
 * Подключённые источники читает общий сбор (через 23 ч после начала прошлого чтения, §103).</p>
 */
@Component
public class CareerScanHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "CAREER_SCAN";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Длина {@code company_site.career_url} в схеме. */
    private static final int MAX_URL = 2000;
    /** Пробные адреса кадровой страницы на сайте, когда на главной нет ссылки или главная закрыта. */
    private static final List<String> CAREER_PATHS = List.of("/kariera", "/sk/kariera", "/careers", "/kariera-a-praca",
            "/pre-uchadzacov");
    /** Пробные кадровые поддомены домена сайта. */
    private static final List<String> CAREER_SUBDOMAINS = List.of("kariera", "jobs", "careers");
    /** Пробные кадровые хосты бренда сайта ({@code %s} — первое слово регистрируемого домена), как в скрипте замера. */
    private static final List<String> BRAND_CAREER_HOSTS = List.of("jobs.%s.com", "careers.%s.com", "www.%s-jobs.sk");
    private static final Pattern IP_ADDRESS = Pattern.compile("[0-9.]+|\\[.*]");
    private static final Logger LOG = LoggerFactory.getLogger(CareerScanHandler.class);

    private final CareerPages pages;
    private final CareerScanRepository repository;
    private final CareerProperties properties;
    private final int parallelSites;
    private final DomainMarketplaces marketplaces;

    /**
     * @param pages         страницы сайтов и источники кадровой страницы
     * @param repository    сайты и источники
     * @param properties    схема, сайтов за задание, срок перепроверки
     * @param parallelSites сколько сайтов задания проверяется одновременно ({@code app.career.parallel-sites})
     * @param marketplaces  площадки продажи доменов (§135)
     */
    public CareerScanHandler(CareerPages pages, CareerScanRepository repository,
            CareerProperties properties, @Value("${app.career.parallel-sites:6}") int parallelSites,
            DomainMarketplaces marketplaces) {
        this.pages = pages;
        this.repository = repository;
        this.properties = properties;
        this.parallelSites = parallelSites;
        this.marketplaces = marketplaces;
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
        List<Site> checked;
        try {
            checked = checkInParallel(sites, providers);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new TaskOutcome.Retry("CAREER_SCAN interrupted", Duration.ZERO);
        }
        if (sites.size() == properties.sitesPerTask() || checked.size() < sites.size()) {
            long last = checked.stream().mapToLong(Site::id).max().orElse(sites.get(sites.size() - 1).id());
            return new TaskOutcome.Continue(continuationKey(day, last), payload(day));
        }
        return new TaskOutcome.Done();
    }

    /**
     * Сайты задания проверяются одновременно, не больше {@code parallelSites} (§77): почти всё время проверки — ожидание
     * ответов сайтов, поэтому по одному задание не укладывалось в аренду. Вежливость к хостам не меняется — промежуток
     * между запросами к одному хосту держит общий бюджет в БД ({@code app.http.politeness.host-interval}). Сайты задания
     * принадлежат разным компаниям (не больше одного сайта компании за задание, §80), итог каждого — своей транзакцией.
     *
     * <p>Срок задания — {@link CareerProperties#checkTimeBudget()} (аудит §78): после него новые сайты не начинаются,
     * остаются непроверенными и достаются следующему заданию цепочки (ключ продолжения — по последнему проверенному
     * сайту); начатые проверки доделываются. Ошибка проверки одного сайта (ошибка кода или данных) завершает задание
     * ошибкой, как и до §77 ({@code FAILED}; недоступность БД — повтор задания); остальные сайты к этому моменту уже
     * записаны.</p>
     *
     * @return проверенные сайты
     * @throws InterruptedException поток задания прерван — задание повторяется
     */
    private List<Site> checkInParallel(List<Site> sites, Set<String> providers) throws InterruptedException {
        long deadline = System.nanoTime() + properties.checkTimeBudget().toNanos();
        List<Callable<Optional<Site>>> checks = sites.stream().map(site -> (Callable<Optional<Site>>) () -> {
            if (System.nanoTime() - deadline > 0) {
                return Optional.empty();
            }
            check(site, providers);
            return Optional.of(site);
        }).toList();
        int threads = Math.max(1, Math.min(parallelSites, sites.size()));
        List<Site> checked = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name("career-scan-", 0).factory())) {
            for (Future<Optional<Site>> done : pool.invokeAll(checks)) {
                done.get().ifPresent(checked::add);
            }
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("CAREER_SCAN site check failed", failed.getCause());
        }
        return checked;
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
        HttpResult homeResult = pages.get(home);
        if (homeResult instanceof HttpResult.PermanentFailure failure
                && failure.kind() == HttpResult.Kind.USE_FORBIDDEN) {
            repository.record(site, Set.of(), CheckResult.USE_FORBIDDEN, null, Role.EMPLOYER);
            return;
        }
        if (homeResult instanceof HttpResult.Success forSale
                && marketplaces.forSale(forSale.locationOr(home).getHost())) {
            LOG.info("Site {} of company {} is a domain for sale ({}), rejected", site.host(), site.companyId(),
                    forSale.locationOr(home));
            repository.rejectForSale(site);
            return;
        }
        Optional<Document> homePage = homeResult instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(home).toString())) : Optional.empty();
        Set<Board> boards = new LinkedHashSet<>(homePage.map(CareerLinks::boards).orElse(Set.of()));
        // Стартовый адрес — сама доска системы найма (ссылка со страницы компании на profesia.sk, §82).
        CareerLinks.board(home.toString()).ifPresent(boards::add);
        Optional<String> careerLink = homePage.flatMap(page -> CareerLinks.careerPage(page,
                URI.create(page.location()).getAuthority()));
        Optional<Document> careerPage = Optional.empty();
        if (boards.isEmpty()) {
            careerPage = careerLink.flatMap(link -> page(URI.create(link))).filter(CareerLinks::isCareerPage);
            if (careerPage.isEmpty()) {
                careerPage = homePage.filter(page -> CareerLinks.careerSite(page.location())
                        && CareerLinks.isCareerPage(page));
            }
            if (careerPage.isEmpty()) {
                careerPage = standardCareerPage(home, hostAnswers(homeResult));
            }
            boards = careerPage.map(page -> careerBoards(page, true)).orElse(Set.of());
            if (boards.isEmpty() && careerPage.isPresent()) {
                Optional<Document> careerHost = standardCareerPage(home, false);
                Set<Board> hostBoards = careerHost.map(page -> careerBoards(page, true)).orElse(Set.of());
                if (!hostBoards.isEmpty()) {
                    careerPage = careerHost;
                    boards = hostBoards;
                }
            }
        }
        if (homePage.isEmpty() && careerPage.isEmpty()) {
            LOG.info("Home page of site {} of company {} did not open ({}), no career address open", site.host(),
                    site.companyId(), homeResult);
            repository.record(site, Set.of(), CheckResult.UNREACHABLE, null, Role.EMPLOYER);
            return;
        }
        String careerUrl = careerUrl(careerPage, careerLink, boards);
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
        repository.record(site, permitted, result, careerUrl, role.orElse(Role.EMPLOYER));
        LOG.info("Site {} of company {}: {} {}", site.host(), site.companyId(), result, boards);
    }

    /**
     * Адрес кадровой страницы для записи: конечный адрес найденной кадровой страницы; доски на главной — ссылка с
     * главной (страница не открывалась); иначе {@code null} — кадровая страница не найдена, отвергнутая ссылка не
     * записывается (аудит §78). Адрес длиннее колонки ({@value #MAX_URL}) не записывается.
     */
    private static String careerUrl(Optional<Document> careerPage, Optional<String> careerLink, Set<Board> boards) {
        String url = careerPage.map(Document::location).orElse(boards.isEmpty() ? null : careerLink.orElse(null));
        return url == null || url.length() > MAX_URL ? null : url;
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
        for (URI candidate : careerCandidates(home, hostAnswers)) {
            Optional<Document> page = careerPage(candidate, home);
            if (page.isPresent()) {
                return page;
            }
        }
        return Optional.empty();
    }

    /**
     * Пробные кадровые адреса по порядку: пути на сайте (если хост отвечает), кадровые поддомены домена сайта,
     * кадровые хосты бренда (первое слово регистрируемого домена); схема — схема главной. У сайта по IP-адресу —
     * только пути.
     *
     * @param home        главная (стартовая страница) сайта
     * @param hostAnswers хост сайта отвечает — пробовать пути на нём
     * @return адреса для проверки
     */
    static List<URI> careerCandidates(URI home, boolean hostAnswers) {
        List<URI> candidates = new ArrayList<>();
        if (hostAnswers) {
            CAREER_PATHS.stream().map(home::resolve).forEach(candidates::add);
        }
        String domain = home.getHost().startsWith("www.") ? home.getHost().substring(4) : home.getHost();
        if (!IP_ADDRESS.matcher(domain).matches()) {
            CAREER_SUBDOMAINS.forEach(sub -> candidates.add(
                    URI.create(home.getScheme() + "://" + sub + "." + domain + "/")));
            String brand = SiteBrand.registrable(domain).split("\\.")[0];
            BRAND_CAREER_HOSTS.forEach(pattern -> candidates.add(
                    URI.create(home.getScheme() + "://" + pattern.formatted(brand) + "/")));
        }
        return candidates;
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

    /**
     * Главная сайта: тот же хост (без {@code www.}) и пустой путь или путь стартовой страницы ({@code /sk/} у сайта со
     * стартовым адресом с портала или из Wikidata, аудит §78).
     */
    private static boolean isHome(URI address, URI home) {
        String path = trimmedPath(address);
        return withoutWww(address.getHost()).equals(withoutWww(home.getHost()))
                && (path.isEmpty() || path.equals(trimmedPath(home)));
    }

    private static String trimmedPath(URI address) {
        return address.getPath() == null ? "" : address.getPath().replaceAll("/+$", "");
    }

    private static String withoutWww(String host) {
        String lower = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return lower.startsWith("www.") ? lower.substring(4) : lower;
    }

    private Set<Board> careerBoards(Document careerPage, boolean deeper) {
        return pages.boards(careerPage, deeper);
    }

    private Optional<Document> page(URI uri) {
        return pages.page(uri);
    }
}
