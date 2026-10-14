package com.roleorienta.worker.stateportal;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Общий список вакансий портала (стенограмма §105) на заглушке: полный обход страницами по две записи, по две
 * страницы за задание; IČO — по списку работодателей портала, поиском работодателей по названию, со страницы своей
 * вакансии; объявление площадки без IČO записывается без него; лента «новые сверху» номер обхода не меняет.
 */
@SpringBootTest(properties = {"app.http.allow-private-addresses=true", "app.state-portal.list.page-size=2",
        "app.state-portal.list.pages-per-task=2"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PortalListTests {

    private static final String LISTED = "Listed s. r. o.";
    private static final String LISTED_NUMBER = "90000011";
    private static final String SEARCHED = "Searched a. s.";
    private static final String DETAIL = "Detail spol.";
    private static final String NOBODY = "Nobody";
    private static final String UUID_A = "0a0a0a0a-1111-4111-8111-000000000001";
    private static final String UUID_C = "0a0a0a0a-1111-4111-8111-000000000003";
    private static final String UUID_E = "0a0a0a0a-1111-4111-8111-000000000005";
    private static final String UUID_F = "0a0a0a0a-1111-4111-8111-000000000006";
    /** Полный обход «старые сверху»: страницы по две записи — 2, 2, 1. */
    private static final List<String> OLDEST = List.of(
            own(UUID_A, "Účtovník", LISTED),
            external("https://www.profesia.sk/praca/searched/O123456", "Skladník", SEARCHED),
            own(UUID_C, "Vodič", DETAIL),
            external("https://www.worki.sk/ponuka/1", "Kuchár", NOBODY),
            own(UUID_E, "Predavač (muž/žena)", LISTED));
    private static final int MAX_TASK_ROUNDS = 10;
    private static final AtomicInteger NAME_SEARCHES = new AtomicInteger();
    private static final AtomicInteger DETAILS = new AtomicInteger();
    private static final HttpServer PORTAL = startPortal();

    @Autowired
    private PortalListHandler handler;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Портал — заглушка.
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void portalProperties(DynamicPropertyRegistry registry) {
        registry.add("app.state-portal.base-url", () -> "http://127.0.0.1:" + PORTAL.getAddress().getPort());
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopPortal() {
        PORTAL.stop(0);
    }

    /**
     * Чистые таблицы общего списка; в списке работодателей портала — {@link #LISTED}.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM portal_offer");
        jdbcTemplate.update("DELETE FROM portal_cycle");
        jdbcTemplate.update("DELETE FROM portal_employer_name");
        jdbcTemplate.update("DELETE FROM portal_employer");
        jdbcTemplate.update("INSERT INTO portal_employer (registration_number, name) VALUES (?, ?)", LISTED_NUMBER,
                LISTED);
        NAME_SEARCHES.set(0);
        DETAILS.set(0);
    }

    /**
     * Полный обход: все вакансии с номером обхода, IČO — тремя путями, у объявления площадки без IČO — пусто;
     * название — с пометкой «(muž/žena)», если её не было; обход завершён, второй сразу не начинается.
     */
    @Test
    void fullCycleStoresOffersWithRegistrationNumbers() {
        assertThat(handler.startCycleIfDue()).isTrue();
        runQueuedTasks();

        assertThat(offers()).containsExactlyInAnyOrder(
                UUID_A + "|" + LISTED_NUMBER + "|Účtovník (muž/žena)",
                "profesia:123456|90000012|Skladník (muž/žena)",
                UUID_C + "|00000123|Vodič (muž/žena)",
                "https://www.worki.sk/ponuka/1|-|Kuchár (muž/žena)",
                UUID_E + "|" + LISTED_NUMBER + "|Predavač (muž/žena)");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM portal_offer "
                + "WHERE last_seen_cycle = (SELECT id FROM portal_cycle WHERE completed_at IS NOT NULL)",
                Integer.class)).isEqualTo(OLDEST.size());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE task_key LIKE 'portal-list:cycle:%'",
                Integer.class)).isEqualTo(2);
        assertThat(handler.startCycleIfDue()).isFalse();
    }

    /**
     * Следующий обход не ищет IČO заново: известные — у вакансий, название без результата — до срока перепроверки.
     */
    @Test
    void secondCycleDoesNotSearchAgain() {
        handler.startCycleIfDue();
        runQueuedTasks();
        assertThat(NAME_SEARCHES.get()).isEqualTo(3);
        assertThat(DETAILS.get()).isEqualTo(1);
        jdbcTemplate.update("UPDATE portal_cycle SET started_at = now() - interval '1 day'");

        assertThat(handler.startCycleIfDue()).isTrue();
        runQueuedTasks();

        assertThat(NAME_SEARCHES.get()).isEqualTo(3);
        assertThat(DETAILS.get()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM portal_cycle WHERE completed_at IS NOT NULL",
                Integer.class)).isEqualTo(2);
    }

    /**
     * Лента «новые сверху» добавляет вакансию без номера обхода; повтор в тот же час ничего не ставит.
     */
    @Test
    void feedAddsNewestOffersWithoutCycle() {
        assertThat(handler.enqueueFeed("2026-10-14T10")).isTrue();
        assertThat(handler.enqueueFeed("2026-10-14T10")).isFalse();
        runQueuedTasks();

        assertThat(jdbcTemplate.queryForList("SELECT external_id || '|' || registration_number || '|' "
                + "|| coalesce(last_seen_cycle::text, '-') FROM portal_offer", String.class))
                .containsExactly(UUID_F + "|" + LISTED_NUMBER + "|-");
    }

    private List<String> offers() {
        return jdbcTemplate.queryForList("SELECT external_id || '|' || coalesce(registration_number, '-') || '|' "
                + "|| title FROM portal_offer", String.class);
    }

    private void runQueuedTasks() {
        for (int round = 0; round < MAX_TASK_ROUNDS; round++) {
            // продолжение обхода ждёт начала следующего часа (квота, §101) — в тесте ставится сразу
            jdbcTemplate.update("UPDATE task SET state = 'QUEUED', next_attempt_at = NULL "
                    + "WHERE state = 'WAITING' AND last_error IS NULL");
            List<Long> queued = jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id",
                    Long.class);
            if (queued.isEmpty()) {
                return;
            }
            queued.forEach(executor::execute);
        }
    }

    private static String own(String uuid, String title, String employer) {
        return "{\"uuid\":\"" + uuid + "\",\"nazovPracovnehoMiesta\":\"" + title + "\",\"zamestnavatelObchodneMeno\":\""
                + employer + "\",\"miestoVykonuPrace\":\"Bratislava\",\"priznakZdroj\":1,\"urlExternyPortal\":null}";
    }

    private static String external(String url, String title, String employer) {
        return "{\"uuid\":\"0b0b0b0b-2222-4222-8222-000000000000\",\"nazovPracovnehoMiesta\":\"" + title
                + "\",\"zamestnavatelObchodneMeno\":\"" + employer + "\",\"miestoVykonuPrace\":\"Košice\",\"priznakZdroj\":3,\"externyPortal\":\"x\","
                + "\"urlExternyPortal\":\"" + url + "\"}";
    }

    /**
     * Заглушка портала: список (старые сверху — {@link #OLDEST} страницами; новые сверху — одна вакансия
     * {@link #UUID_F}), поиск работодателей по названию, страница вакансии {@link #UUID_C} с IČO 123.
     */
    private static HttpServer startPortal() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/search/ponuky", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                List<String> records;
                if (query.contains("sort=OD_NAJNOVSICH")) {
                    records = List.of(own(UUID_F, "Pokladník", LISTED));
                } else {
                    int size = Integer.parseInt(query.replaceAll(".*pageSize=(\\d+).*", "$1"));
                    int page = Integer.parseInt(query.replaceAll(".*pageNr=(\\d+).*", "$1"));
                    int from = Math.min((page - 1) * size, OLDEST.size());
                    records = OLDEST.subList(from, Math.min(from + size, OLDEST.size()));
                }
                respond(exchange, "{\"countVPM\":" + OLDEST.size() + ",\"pracovnePonuky\":["
                        + String.join(",", records) + "]}");
            });
            server.createContext("/enum/vyhladavanie/zamestnavatelia", exchange -> {
                NAME_SEARCHES.incrementAndGet();
                String name = URLDecoder.decode(exchange.getRequestURI().getRawQuery()
                        .replaceAll(".*castNazvu=([^&]*).*", "$1"), StandardCharsets.UTF_8);
                respond(exchange, SEARCHED.equals(name)
                        ? "[{\"ico\":\"90000012\",\"nazov\":\"" + SEARCHED + "\"},"
                                + "{\"ico\":\"90000013\",\"nazov\":\"" + SEARCHED + " Praha\"}]"
                        : "[]");
            });
            server.createContext("/pracovne-ponuky/" + UUID_C, exchange -> {
                DETAILS.incrementAndGet();
                respond(exchange, "<html><body><dl><div><dt class=\"vpm-data-panel__key\">IČO</dt>"
                        + "<dd class=\"vpm-data-panel__value\">123</dd></div></dl></body></html>");
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                body.startsWith("<") ? "text/html; charset=utf-8" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
