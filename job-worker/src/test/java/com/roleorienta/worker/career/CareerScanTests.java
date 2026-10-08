package com.roleorienta.worker.career;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Проверка сайта компании на тестовом сайте (встроенный HTTP-сервер JDK) и настоящей PostgreSQL:
 * ссылка на доску Workday с главной; кадровая страница с разметкой {@code JobPosting} на странице
 * вакансии; сайт без кадровой страницы. Источник подключается и связывается с компанией, итог
 * сохраняется с причиной.
 */
@SpringBootTest(properties = {"app.career.scheme=http", "app.http.allow-private-addresses=true"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CareerScanTests {

    private static final Map<String, String> PAGES = new ConcurrentHashMap<>();
    /** Страница-заглушка: ответ 403. */
    private static final String FORBIDDEN = "403";
    /** Страница-заглушка: ответ 500. */
    private static final String SERVER_ERROR = "500";
    /** Страница-заглушка: переадресация 302 на адрес после префикса. */
    private static final String REDIRECT = "redirect:";
    private static final HttpServer SITE = startSite();
    private static final String HOST = "127.0.0.1:" + SITE.getAddress().getPort();

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExternalHttpClient http;

    @Autowired
    private CareerScanRepository repository;

    /**
     * Остановка сайта.
     */
    @AfterAll
    static void stopSite() {
        SITE.stop(0);
    }

    /**
     * Чистые таблицы, компания с подтверждённым сайтом — тестовым сервером.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM source_permission WHERE scope = 'AGENCY'");
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        for (String table : new String[] {"company_check", "company_source", "company_site", "source", "company", "outbox_event",
                "task"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        PAGES.clear();
        jdbcTemplate.update("INSERT INTO company (country, registration_number, name, registry) "
                + "VALUES ('SK', '11111111', 'Alfa s.r.o.', 'RPO')");
        jdbcTemplate.update("INSERT INTO company_site (company_id, host, evidence_url, source, proof) "
                + "SELECT id, ?, 'http://' || ? || '/kontakt', 'COMMON_CRAWL', 'REGISTRATION_NUMBER' FROM company",
                HOST, HOST);
    }

    /**
     * Главная ссылается на доску Workday — подключён источник Workday со страной Словакия.
     */
    @Test
    void connectsWorkdayBoardLinkedFromHomePage() {
        PAGES.put("/", "<a href=\"https://alfa.wd3.myworkdayjobs.com/sk-SK/Careers\">Kariéra</a>");

        runScan();

        assertThat(connectedSources()).containsExactly("workday:alfa.wd3.myworkdayjobs.com/careers:SK");
        assertThat(checkResult()).isEqualTo("SOURCE_FOUND");
        assertThat(companyResult()).isEqualTo("CONNECTED");
    }

    /**
     * Кадровая страница без досок, но страница вакансии с {@code JobPosting} — кадровая страница
     * подключена как источник {@code jobposting}.
     */
    @Test
    void connectsCareerPageWithJobPostingMarkup() {
        PAGES.put("/", "<a href=\"/kariera\">Kariéra</a>");
        PAGES.put("/kariera", "<h1>Kariéra</h1><a href=\"/kariera/java\">Java Developer</a>");
        PAGES.put("/kariera/java", "<script type=\"application/ld+json\">{\"@type\": \"JobPosting\"}</script>");

        runScan();

        assertThat(connectedSources()).containsExactly("jobposting:http://" + HOST + "/kariera:SK");
        assertThat(checkResult()).isEqualTo("SOURCE_FOUND");
    }

    /**
     * Адрес стартовой страницы с путём (с портала) — поиск начинается с него, а не с главной хоста.
     */
    @Test
    void startsFromStartUrl() {
        jdbcTemplate.update("UPDATE company_site SET start_url = 'http://' || host || '/sk/'");
        PAGES.put("/sk/", "<a href=\"https://alfa.wd3.myworkdayjobs.com/sk-SK/Careers\">Kariéra</a>");

        runScan();

        assertThat(connectedSources()).containsExactly("workday:alfa.wd3.myworkdayjobs.com/careers:SK");
    }

    /**
     * Ключ продолжения цепочки содержит день постановки: тот же последний сайт в другой день — другой ключ, иначе
     * продолжение не ставилось (ключ задания уникален, задания хранятся) и цепочка дня обрывалась (§57).
     */
    @Test
    void continuationKeyDependsOnDay() {
        assertThat(CareerScanHandler.continuationKey("2026-10-06", 7))
                .isNotEqualTo(CareerScanHandler.continuationKey("2026-10-07", 7));
    }

    /**
     * Проверяются основной сайт и прочие найденные сайты, кроме сайтов из Common Crawl (§80): у компании сайт из
     * Wikidata (основной), сайт по названию ({@code localhost} — та же заглушка) и сайт из Common Crawl. Задание берёт
     * не больше одного сайта компании; сайт из Common Crawl не проверяется; итог компании — лучший из её сайтов.
     */
    @Test
    void scansMainAndOtherSitesExceptCommonCrawl() {
        jdbcTemplate.update("UPDATE company_site SET source = 'WIKIDATA', proof = 'WIKIDATA'");
        jdbcTemplate.update("INSERT INTO company_site (company_id, host, evidence_url, source, proof) "
                + "SELECT id, 'other-site.invalid', 'http://other-site.invalid/kontakt', 'COMMON_CRAWL', "
                + "'REGISTRATION_NUMBER' FROM company");
        String byName = "localhost:" + SITE.getAddress().getPort();
        jdbcTemplate.update("INSERT INTO company_site (company_id, host, evidence_url, source, proof) "
                + "SELECT id, ?, 'http://' || ? || '/', 'NAME', 'BRAND' FROM company", byName, byName);
        PAGES.put("/", "<p>Vitajte</p>");

        runScan();

        assertThat(siteResults()).containsExactly(HOST + ":NO_CAREER_PAGE", "other-site.invalid:null", byName + ":null");
        assertThat(companyResult()).isEqualTo("PAGE_NOT_FOUND");

        PAGES.put("/", "<a href=\"https://alfa.wd3.myworkdayjobs.com/sk-SK/Careers\">Kariéra</a>");
        runScan();

        assertThat(siteResults()).containsExactly(HOST + ":NO_CAREER_PAGE", "other-site.invalid:null",
                byName + ":SOURCE_FOUND");
        assertThat(companyResult()).isEqualTo("CONNECTED");
    }

    private List<String> siteResults() {
        return jdbcTemplate.queryForList("SELECT host || ':' || coalesce(check_result, 'null') FROM company_site "
                + "ORDER BY id", String.class);
    }

    /**
     * Кандидат (сайт без доказательства принадлежности) поиском кадровой страницы не проверяется и итог
     * компании не меняет.
     */
    @Test
    void doesNotScanCandidateSite() {
        jdbcTemplate.update("UPDATE company_site SET status = 'CANDIDATE', proof = 'GROUP_SITE'");
        PAGES.put("/", "<a href=\"https://alfa.wd3.myworkdayjobs.com/sk-SK/Careers\">Kariéra</a>");

        runScan();

        assertThat(connectedSources()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT checked_at IS NULL FROM company_site", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_check", Integer.class)).isZero();
    }

    /**
     * Кадровой страницы нет — источника нет, причина сохранена; кадровая страница без
     * поддерживаемого формата — другая причина и её адрес (замер систем найма).
     */
    @Test
    void recordsReasonWhenNothingFound() {
        PAGES.put("/", "<a href=\"/o-nas\">O nás</a>");
        runScan();
        assertThat(connectedSources()).isEmpty();
        assertThat(checkResult()).isEqualTo("NO_CAREER_PAGE");
        assertThat(companyResult()).isEqualTo("PAGE_NOT_FOUND");

        jdbcTemplate.update("UPDATE company_site SET checked_at = NULL");
        PAGES.put("/", "<a href=\"/kariera\">Kariéra</a>");
        PAGES.put("/kariera", "<h1>Kariéra</h1><p>Pošlite životopis na hr@alfa.sk</p>");
        runScan();
        assertThat(checkResult()).isEqualTo("FORMAT_UNSUPPORTED");
        assertThat(companyResult()).isEqualTo("FORMAT_UNSUPPORTED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_check", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT career_url FROM company_site", String.class))
                .isEqualTo("http://" + HOST + "/kariera");
    }

    /**
     * Сценарий 11 (бизнес-описание §7.4): доска кадрового агентства без разрешения на использование
     * не подключается — итог «использование запрещено»; с разрешением на это агентство подключается с
     * ролью «размещающее агентство». (Сайт без IČO не подтверждается — {@code SiteScanTests}; страница,
     * на которую ссылается подтверждённый сайт, подключается — {@link #connectsWorkdayBoardLinkedFromHomePage}.)
     */
    @Test
    void agencyBoardIsConnectedOnlyWithPermission() {
        jdbcTemplate.update("UPDATE company SET agency = TRUE");
        PAGES.put("/", "<a href=\"https://alfa.wd3.myworkdayjobs.com/Careers\">Kariéra</a>");

        runScan();

        assertThat(connectedSources()).isEmpty();
        assertThat(checkResult()).isEqualTo("USE_FORBIDDEN");
        assertThat(companyResult()).isEqualTo("USE_FORBIDDEN");

        jdbcTemplate.update("""
                INSERT INTO source_permission (scope, company_id, decision, basis, checked_on)
                SELECT 'AGENCY', id, 'ALLOW', 'test', current_date FROM company
                """);
        jdbcTemplate.update("UPDATE company_site SET checked_at = NULL");
        runScan();

        assertThat(connectedSources()).containsExactly("workday:alfa.wd3.myworkdayjobs.com/careers:SK");
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM company_source", String.class)).isEqualTo("AGENCY");
        assertThat(companyResult()).isEqualTo("CONNECTED");
    }

    /**
     * На главной нет кадровой ссылки — стандартный адрес {@code /kariera} с кадровым словом в заголовке;
     * на нём кадровая ссылка вглубь, а там доска Workday — подключена.
     */
    @Test
    void findsStandardCareerPageAndGoesOneStepDeeper() {
        PAGES.put("/", "<p>Vitajte</p>");
        PAGES.put("/kariera", "<h1>Kariéra</h1><a href=\"/kariera/volne-pozicie\">Voľné pozície</a>");
        PAGES.put("/kariera/volne-pozicie", "<a href=\"https://alfa.wd3.myworkdayjobs.com/Careers\">Pozície</a>");

        runScan();

        assertThat(connectedSources()).containsExactly("workday:alfa.wd3.myworkdayjobs.com/careers:SK");
        assertThat(jdbcTemplate.queryForObject("SELECT career_url FROM company_site", String.class))
                .isEqualTo("http://" + HOST + "/kariera");
    }

    /**
     * Главная отвечает 403 (сайт закрыт для программы, ограничение не обходится): кадровая страница
     * ищется по пробным адресам и подключается; закрыто всё — итог «сайт не ответил», у компании —
     * «источник недоступен».
     */
    @Test
    void closedHomePageFallsBackToCareerAddresses() {
        PAGES.put("/", FORBIDDEN);
        runScan();
        assertThat(checkResult()).isEqualTo("UNREACHABLE");
        assertThat(companyResult()).isEqualTo("SOURCE_UNAVAILABLE");

        jdbcTemplate.update("UPDATE company_site SET checked_at = NULL");
        PAGES.put("/sk/kariera", "<h1>Kariéra</h1><a href=\"https://alfa.wd3.myworkdayjobs.com/Careers\">Pozície</a>");
        runScan();
        assertThat(connectedSources()).containsExactly("workday:alfa.wd3.myworkdayjobs.com/careers:SK");
        assertThat(companyResult()).isEqualTo("CONNECTED");
    }

    /**
     * Кадровая страница — как в скрипте замера (§76): ссылка «Spolupráca» — не кадровая (в словаре нет голого
     * «práca»), страница по ссылке «Kariéra» без кадровых слов в тексте не засчитывается — пробуются пробные адреса
     * ({@code /kariera-a-praca}); главная не открылась (404) — пробные адреса тоже пробуются; ссылка на раздел главной
     * с кадровым словом в якоре ({@code /#kariera}) засчитывается, конечный адрес — главная.
     */
    @Test
    void findsCareerPageLikeSurveyScript() {
        PAGES.put("/", "<a href=\"/spolupraca\">Spolupráca</a><a href=\"/kariera-info\">Kariéra</a>");
        PAGES.put("/spolupraca", "<h1>Spolupráca s partnermi</h1>");
        PAGES.put("/kariera-info", "<p>Stránka sa pripravuje</p>");
        PAGES.put("/kariera-a-praca", "<h1>Práca u nás</h1><p>Pošlite životopis</p>");

        runScan();

        assertThat(checkResult()).isEqualTo("FORMAT_UNSUPPORTED");
        assertThat(jdbcTemplate.queryForObject("SELECT career_url FROM company_site", String.class))
                .isEqualTo("http://" + HOST + "/kariera-a-praca");

        jdbcTemplate.update("UPDATE company_site SET checked_at = NULL");
        PAGES.remove("/");
        runScan();
        assertThat(checkResult()).isEqualTo("FORMAT_UNSUPPORTED");
        assertThat(companyResult()).isEqualTo("FORMAT_UNSUPPORTED");

        jdbcTemplate.update("UPDATE company_site SET checked_at = NULL");
        PAGES.clear();
        PAGES.put("/", "<h1>Kariéra v Alfe</h1><a href=\"/#kariera\">Pozície</a>");
        runScan();
        assertThat(checkResult()).isEqualTo("FORMAT_UNSUPPORTED");
        assertThat(jdbcTemplate.queryForObject("SELECT career_url FROM company_site", String.class))
                .isEqualTo("http://" + HOST + "/");
    }

    /**
     * Сайты задания проверяются одновременно (§77): у трёх компаний сайт со ссылкой на одну доску Workday — все три
     * проверены одним заданием, доска подключена один раз и связана с каждой компанией.
     */
    @Test
    void checksSitesOfTaskInParallel() {
        jdbcTemplate.update("INSERT INTO company (country, registration_number, name, registry) "
                + "VALUES ('SK', '22222222', 'Beta s.r.o.', 'RPO'), ('SK', '33333333', 'Gama s.r.o.', 'RPO')");
        jdbcTemplate.update("INSERT INTO company_site (company_id, host, evidence_url, source, proof) "
                + "SELECT id, ?, 'http://' || ? || '/kontakt', 'COMMON_CRAWL', 'REGISTRATION_NUMBER' FROM company "
                + "WHERE registration_number <> '11111111'", HOST, HOST);
        PAGES.put("/", "<a href=\"https://alfa.wd3.myworkdayjobs.com/sk-SK/Careers\">Kariéra</a>");

        runScan();

        assertThat(jdbcTemplate.queryForList("SELECT check_result FROM company_site", String.class))
                .containsExactly("SOURCE_FOUND", "SOURCE_FOUND", "SOURCE_FOUND");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM source", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_source", Integer.class)).isEqualTo(3);
    }

    /**
     * Пробный адрес, переадресованный на главную (сайт отвечает главной на любой адрес), не засчитывается, даже если в
     * меню главной есть «Kariéra»; кадровой страницы нет — адрес не записывается (аудит §78).
     */
    @Test
    void probeRedirectedToHomeIsNotCareerPage() {
        PAGES.put("/", "<nav>Kariéra</nav><p>Vitajte</p>");
        PAGES.put("/kariera", REDIRECT + "/");

        runScan();

        assertThat(checkResult()).isEqualTo("NO_CAREER_PAGE");
        assertThat(jdbcTemplate.queryForObject("SELECT career_url FROM company_site", String.class)).isNull();
    }

    /**
     * Главная отвечает 5xx (хост не отвечает): пробные пути на том же хосте не запрашиваются — итог «сайт не ответил»,
     * хотя {@code /kariera} есть (§76).
     */
    @Test
    void probesNoPathsWhenHostDoesNotAnswer() {
        PAGES.put("/", SERVER_ERROR);
        PAGES.put("/kariera", "<h1>Kariéra</h1>");

        runScan();

        assertThat(checkResult()).isEqualTo("UNREACHABLE");
    }

    /**
     * Пробные адреса: пути (если хост отвечает), кадровые поддомены, кадровые хосты бренда — со схемой главной; у
     * поддомена бренд — первое слово регистрируемого домена.
     */
    @Test
    void careerCandidatesFollowSurveyScript() {
        assertThat(CareerScanHandler.careerCandidates(URI.create("http://sk.acme.com/"), false)).extracting(URI::toString)
                .containsExactly("http://kariera.sk.acme.com/", "http://jobs.sk.acme.com/", "http://careers.sk.acme.com/",
                        "http://jobs.acme.com/", "http://careers.acme.com/", "http://www.acme-jobs.sk/");
        assertThat(CareerScanHandler.careerCandidates(URI.create("https://www.acme.sk/"), true)).hasSize(11)
                .startsWith(URI.create("https://www.acme.sk/kariera"));
    }

    /**
     * Срок задания истёк (0 с) — новые сайты не начинаются, остаются непроверенными; продолжение цепочки поставлено
     * (аудит §78).
     */
    @Test
    void stopsStartingSitesAfterTimeBudget() {
        CareerScanHandler noTime = new CareerScanHandler(http, repository, taskService, new CareerProperties("http", 25,
                Duration.ofDays(30), 20, Duration.ZERO), 6);

        noTime.handle(new TaskRecord(0, CareerScanHandler.TYPE, CareerScanHandler.payload("budget"), 0));

        assertThat(jdbcTemplate.queryForObject("SELECT checked_at IS NULL FROM company_site", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE task_key LIKE 'career-scan:budget:%'",
                Integer.class)).isEqualTo(1);
    }

    private void runScan() {
        String key = CareerScanHandler.taskKey("test-" + System.nanoTime());
        taskService.enqueue(CareerScanHandler.TYPE, key, CareerScanHandler.payload("test"));
        executor.execute(jdbcTemplate.queryForObject("SELECT id FROM task WHERE task_key = ?", Long.class, key));
    }

    private List<String> connectedSources() {
        return jdbcTemplate.queryForList("""
                SELECT s.provider || ':' || s.board || ':' || s.country
                FROM company_source cs JOIN source s ON s.id = cs.source_id ORDER BY 1
                """, String.class);
    }

    private String companyResult() {
        return jdbcTemplate.queryForObject("SELECT result FROM company_check", String.class);
    }

    private String checkResult() {
        return jdbcTemplate.queryForObject("SELECT check_result FROM company_site", String.class);
    }

    private static HttpServer startSite() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String page = PAGES.get(exchange.getRequestURI().getPath());
                if (page != null && page.startsWith(REDIRECT)) {
                    exchange.getResponseHeaders().set("Location", page.substring(REDIRECT.length()));
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                int status = page == null ? 404 : FORBIDDEN.equals(page) ? 403 : SERVER_ERROR.equals(page) ? 500 : 200;
                byte[] bytes = status != 200 ? new byte[0] : ("<html><body>" + page + "</body></html>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
