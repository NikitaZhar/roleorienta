package com.roleorienta.worker.career;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    private static final HttpServer SITE = startSite();
    private static final String HOST = "127.0.0.1:" + SITE.getAddress().getPort();

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.update("INSERT INTO company_site (company_id, host, evidence_url, source) "
                + "SELECT id, ?, 'http://' || ? || '/kontakt', 'COMMON_CRAWL' FROM company", HOST, HOST);
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
        PAGES.put("/kariera", "<a href=\"/kariera/java\">Java Developer</a>");
        PAGES.put("/kariera/java", "<script type=\"application/ld+json\">{\"@type\": \"JobPosting\"}</script>");

        runScan();

        assertThat(connectedSources()).containsExactly("jobposting:http://" + HOST + "/kariera:SK");
        assertThat(checkResult()).isEqualTo("SOURCE_FOUND");
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
        PAGES.put("/kariera", "<p>Pošlite životopis na hr@alfa.sk</p>");
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

    private void runScan() {
        String key = CareerScanHandler.taskKey("test-" + System.nanoTime());
        taskService.enqueue(CareerScanHandler.TYPE, key, CareerScanHandler.payload());
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
                byte[] bytes = page == null ? new byte[0] : ("<html><body>" + page + "</body></html>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(page == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
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
