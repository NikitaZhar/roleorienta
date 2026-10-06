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
import java.util.Map;
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
 * Шаги 3–4 поиска сайта — адрес по названию — на заглушке сайтов и настоящей PostgreSQL (технический документ
 * §5.1): IČO на странице «Kontakt» угаданного адреса — находка; ответ 403 по {@code www.бренд.sk} и сайт группы без
 * второго источника — кандидаты; компания без признака найма проверяется, только если в ней 10+ сотрудников (RÚZ).
 */
@SpringBootTest(properties = "app.http.allow-private-addresses=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SiteNameTests {

    /** IČO на странице «Kontakt» сайта {@code www.alfaplast.sk}. */
    private static final String ALFA = "90200001";
    /** {@code www.betaplast.sk} отвечает 403, {@code www.betaplast.com} — сайт группы. */
    private static final String BETA = "90200002";
    /** Без источника вакансий, 5 сотрудников (RÚZ) — не проверяется. */
    private static final String GAMA = "90200003";
    /** Без источника вакансий, 50 сотрудников (RÚZ) — проверяется. */
    private static final String DELTA = "90200004";
    private static final Map<String, String> NAMES = Map.of(ALFA, "Alfaplast s.r.o.", BETA, "Betaplast a.s.",
            GAMA, "Gamaplast s.r.o.", DELTA, "Deltaplast s.r.o.");
    private static final String GROUP_TEXT = "Betaplast is a global manufacturer of plastic parts for the automotive "
            + "industry with plants on four continents, serving car makers and their suppliers with precise, "
            + "reliable components and engineering support for more than thirty years.";
    private static final int MAX_TASK_ROUNDS = 10;
    private static final HttpServer SERVER = startServer();

    @Autowired
    private SiteNameHandler handler;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Сайты — заглушка: главная хоста — {@code /site/<хост>/}.
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void siteProperties(DynamicPropertyRegistry registry) {
        registry.add("app.site-check.home-url", () -> base() + "/site/{host}/");
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    /**
     * Чистые записи; Словакия — активная страна сбора; у {@link #ALFA} и {@link #BETA} — источник портала (признак
     * найма), у {@link #ALFA} — итог «сайт не найден»; число сотрудников у {@link #GAMA} и {@link #DELTA}.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        String numbers = "'" + String.join("','", NAMES.keySet()) + "'";
        for (String table : List.of("company_check", "company_source", "company_site")) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE company_id IN (SELECT id FROM company WHERE "
                    + "registration_number IN (" + numbers + "))");
        }
        jdbcTemplate.update("DELETE FROM source WHERE provider = 'sluzbyzamestnanosti' AND board IN (" + numbers + ")");
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        NAMES.forEach((number, name) -> jdbcTemplate.update("""
                INSERT INTO company (country, registration_number, name, registry) VALUES ('SK', ?, ?, 'RPO')
                ON CONFLICT (country, registration_number) DO UPDATE SET terminated_on = NULL, name = EXCLUDED.name,
                                                                         site_name_checked_at = NULL
                """, number, name));
        for (String number : List.of(ALFA, BETA)) {
            jdbcTemplate.update("INSERT INTO source (provider, board, country) VALUES ('sluzbyzamestnanosti', ?, 'SK')",
                    number);
            jdbcTemplate.update("""
                    INSERT INTO company_source (company_id, source_id, role)
                    SELECT c.id, s.id, 'EMPLOYER' FROM company c, source s
                    WHERE c.registration_number = ? AND s.provider = 'sluzbyzamestnanosti' AND s.board = ?
                    """, number, number);
        }
        jdbcTemplate.update("UPDATE company SET employees_min = 5 WHERE registration_number = ?", GAMA);
        jdbcTemplate.update("UPDATE company SET employees_min = 50 WHERE registration_number = ?", DELTA);
        jdbcTemplate.update("INSERT INTO company_check (company_id, result, first_checked_at, checked_at) "
                + "SELECT id, 'SITE_NOT_FOUND', now(), now() FROM company WHERE registration_number = ?", ALFA);
    }

    /**
     * Находка по IČO снимает итог «сайт не найден»; у {@link #BETA} — два кандидата; {@link #GAMA} (5 сотрудников) не
     * проверялась, {@link #DELTA} (50) — проверялась;
     * повторный шаг в тот же срок ничего не добавляет.
     */
    @Test
    void recordsSitesByName() {
        handler.enqueue("first");
        runQueuedTasks();

        assertThat(sites()).containsExactly(
                ALFA + ":www.alfaplast.sk:REGISTRATION_NUMBER:FOUND",
                BETA + ":www.betaplast.com:GROUP_SITE:CANDIDATE",
                BETA + ":www.betaplast.sk:HTTP_403:CANDIDATE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_check k JOIN company c ON c.id = "
                + "k.company_id WHERE c.registration_number = ?", Integer.class, ALFA)).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT registration_number || ':' || (site_name_checked_at IS NOT NULL) "
                + "FROM company WHERE registration_number IN (?, ?) ORDER BY 1", String.class, GAMA, DELTA))
                .containsExactly(GAMA + ":false", DELTA + ":true");

        handler.enqueue("second");
        runQueuedTasks();

        assertThat(sites()).hasSize(3);
    }

    private List<String> sites() {
        return jdbcTemplate.queryForList("""
                SELECT c.registration_number || ':' || s.host || ':' || s.proof || ':' || s.status
                FROM company_site s JOIN company c ON c.id = s.company_id
                WHERE s.source = 'NAME' AND c.registration_number IN (?, ?, ?) ORDER BY 1
                """, String.class, ALFA, BETA, GAMA);
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
     * Заглушка сайтов: {@code www.alfaplast.sk} — главная со ссылкой «Kontakt», на ней IČO; {@code www.betaplast.sk}
     * — 403; {@code www.betaplast.com} — главная сайта группы; прочее — 404.
     */
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                switch (exchange.getRequestURI().getPath()) {
                    case "/site/www.alfaplast.sk/" -> respond(exchange, 200,
                            "<html><body><a href=\"kontakt\">Kontakt</a></body></html>");
                    case "/site/www.alfaplast.sk/kontakt" -> respond(exchange, 200,
                            "<html><body><p>Alfaplast s.r.o., IČO: " + ALFA + "</p></body></html>");
                    case "/site/www.betaplast.sk/" -> respond(exchange, 403, "");
                    case "/site/www.betaplast.com/" -> respond(exchange, 200,
                            "<html><head><title>Betaplast</title></head><body><p>" + GROUP_TEXT + "</p></body></html>");
                    default -> respond(exchange, 404, "");
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
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
