package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.collect.ReadSourcePlanner;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Добор объявлений profesia.sk, которых нет на портале (§219), на заглушке profesia.sk и настоящей PostgreSQL: карта
 * сайта → объявления; объявление общего списка портала не берётся; страница прочитана — вакансия в источнике
 * работодателя (провайдер {@code profesia}, без компании) с названием, местом и датой; нет страницы — ничего; ушедшее
 * на портал — закрывается после N полных чтений; чтение по источнику планировщик не ставит.
 */
@SpringBootTest(properties = {"app.http.allow-private-addresses=true", "app.state-portal.profesia-offers.enabled=true"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProfesiaOffersTests {

    private static final Map<String, String> PAGES = new ConcurrentHashMap<>();
    private static final HttpServer PROFESIA = startProfesia();
    private static final String BASE = "http://127.0.0.1:" + PROFESIA.getAddress().getPort();

    @Autowired
    private ProfesiaListHandler listHandler;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ReadSourcePlanner planner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Карта сайта — заглушка.
     *
     * @param registry свойства контекста
     */
    @DynamicPropertySource
    static void sitemap(DynamicPropertyRegistry registry) {
        registry.add("app.state-portal.profesia-sitemap-url", () -> BASE + "/sitemap.php");
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopProfesia() {
        PROFESIA.stop(0);
    }

    /**
     * Чистые задания, объявления, публикации и источники; карта сайта: два объявления Acme (одно — и на портале) и
     * одно Beta (страницы нет).
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "vacancy_status_history", "vacancy_user_status",
                "vacancy_language", "vacancy_position_match", "company_source", "source_snapshot", "vacancy_revision",
                "crawl_run", "job_posting", "vacancy", "outbox_event", "task", "source", "profesia_offer",
                "profesia_employer", "portal_offer"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        PAGES.clear();
        PAGES.put("/sitemap.php", "<urlset><url><loc>" + BASE + "/praca/acme-s-r-o/O101</loc></url>"
                + "<url><loc>" + BASE + "/praca/acme-s-r-o/O102</loc></url>"
                + "<url><loc>" + BASE + "/praca/beta/O103</loc></url></urlset>");
        PAGES.put("/praca/acme-s-r-o/O101", ProfesiaOfferPageTests.PAGE.replace("Brightpick s. r. o.", "ACME s.r.o."));
        PAGES.put("/praca/acme-s-r-o/O102", "<h1>Skladník</h1>");
        portalOffer(102);
    }

    /**
     * Объявление, которого нет на портале, — вакансия; объявление портала и объявление без страницы — нет; источник не
     * связан с компанией и не читается планировщиком.
     */
    @Test
    void recordsOffersMissingFromPortal() {
        runChain();

        assertThat(jdbcTemplate.queryForList("""
                SELECT s.board || ':' || s.employer_name || ':' || p.external_id || ':' || p.title || ':' || p.location
                       || ':' || v.posted_at::date || ':' || v.state
                FROM job_posting p JOIN source s ON s.id = p.source_id JOIN vacancy v ON v.id = p.vacancy_id
                """, String.class)).containsExactly("acme-s-r-o:ACME s.r.o.:profesia:101:DevOps Manager:"
                + "Plynárenská 4937/6, Ružinov, Slovensko, Bratislava:2026-10-25:ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_source", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT page_read_at IS NOT NULL AND title IS NULL FROM profesia_offer "
                + "WHERE num = 103", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT page_read_at IS NULL FROM profesia_offer WHERE num = 102",
                Boolean.class)).isTrue();

        planner.enqueueDue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE type = 'READ_SOURCE'", Integer.class))
                .isZero();
    }

    /** Объявление появилось на портале — у источника profesia.sk отсутствие; после трёх полных чтений закрыто. */
    @Test
    void offerFoundOnPortalCloses() {
        runChain();
        portalOffer(101);

        for (int round = 1; round <= 3; round++) {
            taskService.enqueue(ProfesiaOffersHandler.TYPE, "profesia-offers:close:" + round,
                    ProfesiaOffersHandler.payload("close", 1));
            runQueued();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT state FROM vacancy", String.class)).isEqualTo("CLOSED");
    }

    private void portalOffer(long num) {
        jdbcTemplate.update("""
                INSERT INTO portal_offer (external_id, employer_name, title, url) VALUES (?, 'Acme', 'Offer', ?)
                ON CONFLICT (external_id) DO UPDATE SET last_seen_at = now()
                """, "profesia:" + num, "https://www.profesia.sk/O" + num);
    }

    private void runChain() {
        listHandler.enqueue("test");
        for (int pass = 0; pass < 5; pass++) {
            runQueued();
        }
    }

    private void runQueued() {
        // продолжение цепочки ждёт начала следующего часа (квота, §101) — в тесте ставится сразу
        jdbcTemplate.update("UPDATE task SET state = 'QUEUED', next_attempt_at = NULL "
                + "WHERE state = 'WAITING' AND last_error IS NULL");
        List<Long> queued = jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id",
                Long.class);
        queued.forEach(executor::execute);
    }

    /**
     * Заглушка profesia.sk: путь → страница из {@link #PAGES}; нет — 404.
     */
    private static HttpServer startProfesia() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String page = PAGES.get(exchange.getRequestURI().getPath());
                byte[] bytes = page == null ? new byte[0] : page.getBytes(StandardCharsets.UTF_8);
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
