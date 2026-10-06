package com.roleorienta.worker.intake;

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
 * Шаг «число сотрудников из RÚZ» на заглушке RÚZ Open API и настоящей PostgreSQL (технический документ §5.1):
 * категория записывается нижней границей; несколько учётных единиц — берётся последняя; категория не указана и
 * компании нет в RÚZ или RÚZ отказал (404) — неизвестно; спрошенная компания второй раз в срок не спрашивается.
 */
@SpringBootTest(properties = "app.http.allow-private-addresses=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CompanySizeTests {

    /** Две учётные единицы: старая «5-9», последняя «50-99» — 50. */
    private static final String MEDIUM = "90300001";
    /** Категория «00 nezistený» — неизвестно. */
    private static final String UNDISCLOSED = "90300002";
    /** Нет в RÚZ — неизвестно. */
    private static final String ABSENT = "90300003";
    /** RÚZ отвечает 404 (постоянный отказ) — неизвестно, записано: компания не застревает в начале очереди. */
    private static final String REFUSED = "90300004";
    /** Ответ на список учётных единиц по IČO. */
    private static final Map<String, String> UNITS = Map.of(MEDIUM, "{\"id\": [11, 12]}", UNDISCLOSED,
            "{\"id\": [21]}", ABSENT, "{\"id\": []}");
    /** Карточка учётной единицы по id. */
    private static final Map<String, String> CARDS = Map.of("11", "{\"velkostOrganizacie\": \"05\"}", "12",
            "{\"velkostOrganizacie\": \"12\"}", "21", "{\"velkostOrganizacie\": \"00\"}");
    private static final int MAX_TASK_ROUNDS = 10;
    private static final HttpServer SERVER = startServer();

    @Autowired
    private CompanySizeHandler handler;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * RÚZ — заглушка.
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void ruzProperties(DynamicPropertyRegistry registry) {
        registry.add("app.company-size.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/api");
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    /**
     * Чистые задания; Словакия — активная страна сбора; прочие компании базы уже спрошены — задание берёт только
     * компании теста.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        jdbcTemplate.update("UPDATE company SET employees_checked_at = now()");
        for (String number : List.of(MEDIUM, UNDISCLOSED, ABSENT, REFUSED)) {
            jdbcTemplate.update("""
                    INSERT INTO company (country, registration_number, name, registry) VALUES ('SK', ?, ?, 'RPO')
                    ON CONFLICT (country, registration_number) DO UPDATE SET terminated_on = NULL, employees_min = NULL,
                                                                             employees_checked_at = NULL
                    """, number, "Company " + number);
        }
    }

    /**
     * Категории записаны; повторный шаг в срок ничего не меняет.
     */
    @Test
    void recordsEmployeesFromRuz() {
        handler.enqueue("first");
        runQueuedTasks();

        assertThat(sizes()).containsExactly(MEDIUM + ":50", UNDISCLOSED + ":null", ABSENT + ":null", REFUSED + ":null");

        jdbcTemplate.update("UPDATE company SET employees_min = 1 WHERE registration_number = ?", MEDIUM);
        handler.enqueue("second");
        runQueuedTasks();

        assertThat(sizes()).startsWith(MEDIUM + ":1");
    }

    private List<String> sizes() {
        return jdbcTemplate.queryForList("""
                SELECT registration_number || ':' || coalesce(employees_min::text, 'null') FROM company
                WHERE registration_number IN (?, ?, ?, ?) AND employees_checked_at IS NOT NULL ORDER BY 1
                """, String.class, MEDIUM, UNDISCLOSED, ABSENT, REFUSED);
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

    /**
     * Заглушка RÚZ: {@code /api/uctovne-jednotky?…&ico=…} — {@link #UNITS}; {@code /api/uctovna-jednotka?id=…} —
     * {@link #CARDS}.
     */
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/api/uctovne-jednotky", exchange -> respond(exchange,
                    UNITS.get(exchange.getRequestURI().getQuery().replaceAll(".*ico=(\\d+).*", "$1"))));
            server.createContext("/api/uctovna-jednotka", exchange -> respond(exchange,
                    CARDS.get(exchange.getRequestURI().getQuery().replaceAll(".*id=(\\d+).*", "$1"))));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
