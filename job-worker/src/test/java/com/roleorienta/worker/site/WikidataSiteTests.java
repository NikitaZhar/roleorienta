package com.roleorienta.worker.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.task.TaskExecutor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Шаг «Wikidata» поиска сайта на заглушке SPARQL-сервиса и сайтов и настоящей PostgreSQL (технический документ
 * §5.1): постраничный ответ, IČO без ведущего нуля, открывшийся сайт — находка, ответ 403 — находка с
 * {@code WIKIDATA_403}, несуществующая страница — кандидат; компания с найденным сайтом не проверяется.
 */
@SpringBootTest(properties = {"app.http.allow-private-addresses=true", "app.wikidata.page-size=2"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WikidataSiteTests {

    /** Сайт открывается. */
    private static final String OPENS = "90100001";
    /** Сайт отвечает 403. */
    private static final String CLOSED = "90100002";
    /** Сайта нет (404); в Wikidata IČO записан без ведущего нуля. */
    private static final String MISSING = "01234567";
    /** Уже есть найденный сайт — не проверяется. */
    private static final String HAS_SITE = "90100004";
    /** Нет в реестре. */
    private static final String UNKNOWN = "90100005";
    private static final List<String> REGISTERED = List.of(OPENS, CLOSED, MISSING, HAS_SITE);
    /** Строки ответа Wikidata: IČO, путь сайта на заглушке. */
    private static final List<String[]> ROWS = List.of(new String[] {"1234567", "/missing"},
            new String[] {OPENS, "/opens"}, new String[] {CLOSED, "/closed"}, new String[] {HAS_SITE, "/opens"},
            new String[] {UNKNOWN, "/opens"});
    private static final Pattern OFFSET = Pattern.compile("OFFSET[+ ](\\d+)");
    private static final Pattern LIMIT = Pattern.compile("LIMIT[+ ](\\d+)");
    private static final int MAX_TASK_ROUNDS = 10;
    private static final HttpServer SERVER = startServer();

    @Autowired
    private WikidataSiteHandler handler;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * SPARQL-сервис — заглушка.
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void wikidataProperties(DynamicPropertyRegistry registry) {
        registry.add("app.wikidata.endpoint", () -> base() + "/sparql");
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    /**
     * Чистые записи; Словакия — активная страна сбора; компании реестра; у одной — найденный сайт; у всех,
     * кроме неё, — итог «сайт не найден».
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM company_check");
        jdbcTemplate.update("DELETE FROM company_site");
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        for (String number : REGISTERED) {
            jdbcTemplate.update("""
                    INSERT INTO company (country, registration_number, name, registry) VALUES ('SK', ?, ?, 'RPO')
                    ON CONFLICT (country, registration_number) DO UPDATE SET terminated_on = NULL
                    """, number, "Company " + number);
        }
        jdbcTemplate.update("INSERT INTO company_site (company_id, host, evidence_url, source, proof) SELECT id, "
                + "'has-site.sk', 'http://has-site.sk/kontakt', 'COMMON_CRAWL', 'REGISTRATION_NUMBER' FROM company "
                + "WHERE registration_number = ?", HAS_SITE);
        jdbcTemplate.update("INSERT INTO company_check (company_id, result, first_checked_at, checked_at) "
                + "SELECT id, 'SITE_NOT_FOUND', now(), now() FROM company WHERE registration_number IN (?, ?, ?)",
                OPENS, CLOSED, MISSING);
    }

    /**
     * Все страницы ответа прочитаны; сайты записаны по итогу открытия; найденный сайт снимает итог «сайт не
     * найден»; повторный шаг ничего не добавляет и кандидата не проверяет снова.
     */
    @Test
    void recordsWikidataSitesByOpening() {
        handler.enqueue("first");
        runQueuedTasks();

        assertThat(sites()).containsExactly(
                MISSING + ":127.0.0.1:WIKIDATA:WIKIDATA:CANDIDATE:http://www.wikidata.org/entity/Q1234567",
                OPENS + ":127.0.0.1:WIKIDATA:WIKIDATA:FOUND:http://www.wikidata.org/entity/Q" + OPENS,
                CLOSED + ":127.0.0.1:WIKIDATA:WIKIDATA_403:FOUND:http://www.wikidata.org/entity/Q" + CLOSED,
                HAS_SITE + ":has-site.sk:COMMON_CRAWL:REGISTRATION_NUMBER:FOUND:http://has-site.sk/kontakt");
        assertThat(jdbcTemplate.queryForList("SELECT c.registration_number FROM company_check k "
                + "JOIN company c ON c.id = k.company_id", String.class)).containsExactly(MISSING);

        handler.enqueue("second");
        runQueuedTasks();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_site", Integer.class)).isEqualTo(4);
    }

    private List<String> sites() {
        return jdbcTemplate.queryForList("""
                SELECT c.registration_number || ':' || s.host || ':' || s.source || ':' || s.proof || ':'
                       || s.status || ':' || s.evidence_url
                FROM company_site s JOIN company c ON c.id = s.company_id ORDER BY 1
                """, String.class);
    }

    private void runQueuedTasks() {
        for (int round = 0; round < MAX_TASK_ROUNDS; round++) {
            List<Long> queued = jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id",
                    Long.class);
            if (queued.isEmpty()) {
                return;
            }
            queued.forEach(executor::execute);
        }
    }

    private static String base() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    /**
     * Заглушка: {@code /sparql} — строки {@link #ROWS} по {@code LIMIT}/{@code OFFSET} запроса; {@code /opens}
     * — страница, {@code /closed} — 403, прочее — 404.
     */
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/sparql", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                int offset = number(OFFSET, query);
                int limit = number(LIMIT, query);
                StringBuilder body = new StringBuilder("{\"results\": {\"bindings\": [");
                for (int index = offset; index < Math.min(ROWS.size(), offset + limit); index++) {
                    String[] row = ROWS.get(index);
                    body.append(index > offset ? "," : "").append("{\"item\": {\"value\": \"http://www.wikidata.org/entity/Q")
                            .append(row[0]).append("\"}, \"ico\": {\"value\": \"").append(row[0])
                            .append("\"}, \"site\": {\"value\": \"").append(base()).append(row[1]).append("\"}}");
                }
                respond(exchange, 200, body.append("]}}").toString());
            });
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                respond(exchange, "/opens".equals(path) ? 200 : "/closed".equals(path) ? 403 : 404,
                        "<html><body>Company</body></html>");
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int number(Pattern pattern, String query) {
        Matcher matcher = pattern.matcher(query);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
        if (status == 200) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } else {
            exchange.close();
        }
    }
}
