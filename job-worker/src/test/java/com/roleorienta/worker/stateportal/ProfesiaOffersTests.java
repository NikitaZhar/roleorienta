package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.collect.ReadSourcePlanner;
import com.roleorienta.worker.stateportal.ProfesiaOfferRepository.ListedOffer;
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
import org.junit.jupiter.api.AfterEach;
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
    private ProfesiaOfferRepository offers;

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
        PAGES.put("/sitemap.php", "<urlset><url><loc>" + BASE + "/praca/qwacme-s-r-o/O101</loc></url>"
                + "<url><loc>" + BASE + "/praca/qwacme-s-r-o/O102</loc></url>"
                + "<url><loc>" + BASE + "/praca/beta/O103</loc></url>"
                + "<url><loc>" + BASE + "/praca/gamma/O104</loc></url></urlset>");
        PAGES.put("/praca/qwacme-s-r-o/O101", ProfesiaOfferPageTests.PAGE.replace("Brightpick s. r. o.", "ACME s.r.o."));
        PAGES.put("/praca/qwacme-s-r-o/O102", "<h1>Skladník</h1>");
        PAGES.put("/praca/gamma/O104", "<p>Súhlas so súbormi cookies</p>");
        portalOffer(102);
    }

    /**
     * Объявления и работодатели profesia.sk ссылаются на компании: другие тесты удаляют компании (аудит §222).
     */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM profesia_offer");
        jdbcTemplate.update("DELETE FROM profesia_employer");
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
                """, String.class)).containsExactly("qwacme-s-r-o:ACME s.r.o.:profesia:101:DevOps Manager:"
                + "Plynárenská 4937/6, Ružinov, Slovensko, Bratislava:2026-10-25:ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_source", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT page_read_at IS NOT NULL AND title IS NULL AND page_gone "
                + "FROM profesia_offer WHERE num = 103", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT page_read_at IS NULL FROM profesia_offer WHERE num = 102",
                Boolean.class)).isTrue();

        planner.enqueueDue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE type = 'READ_SOURCE'", Integer.class))
                .isZero();
    }

    /**
     * Страница без названия (баннер, капча; аудит §222) — не «объявления нет»: не записывается и запрашивается снова
     * через 7 дней; страница 404 — больше нет.
     */
    @Test
    void pageWithoutTitleIsRequestedAgain() {
        runChain();

        assertThat(jdbcTemplate.queryForObject("SELECT page_read_at IS NOT NULL AND title IS NULL AND NOT page_gone "
                + "FROM profesia_offer WHERE num = 104", Boolean.class)).isTrue();
        assertThat(offers.toRead(10)).isEmpty();
        jdbcTemplate.update("UPDATE profesia_offer SET page_read_at = now() - interval '8 days'");
        assertThat(offers.toRead(10)).extracting(ListedOffer::num).containsExactly(104L);
    }

    /**
     * Карта сайта без объявлений (другой вид, заглушка; аудит §222) — объявления не обновляются, добор не ставится,
     * вакансии не закрываются.
     */
    @Test
    void emptySitemapKeepsOffers() {
        runChain();
        PAGES.put("/sitemap.php", "<urlset></urlset>");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");

        listHandler.enqueue("empty");
        for (int pass = 0; pass < 3; pass++) {
            runQueued();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE type = ?", Integer.class,
                ProfesiaOffersHandler.TYPE)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM vacancy", String.class)).isEqualTo("ACTIVE");
    }

    /** Общий список портала устарел (аудит §222) — «нет на портале» ничего не значит: добор не идёт. */
    @Test
    void stalePortalListStopsOffers() {
        jdbcTemplate.update("UPDATE portal_offer SET last_seen_at = now() - interval '5 days'");

        runChain();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM job_posting", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM profesia_offer WHERE page_read_at IS NOT NULL",
                Integer.class)).isZero();
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

    /**
     * Та же вакансия уже есть у компании работодателя из её кадровой страницы (§220: «читаем компании, портал и только
     * после этого profesia») — название без регистра и пометки «(m/ž)» совпало: объявление не берётся.
     */
    @Test
    void offerKnownFromCompanyIsNotTaken() {
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        long company = jdbcTemplate.queryForObject("""
                INSERT INTO company (country, registration_number, name, registry) VALUES ('SK', '90600001', 'Qwacme s.r.o.', 'RPO')
                ON CONFLICT (country, registration_number) DO UPDATE SET name = EXCLUDED.name, terminated_on = NULL
                RETURNING id
                """, Long.class);
        long source = jdbcTemplate.queryForObject(
                "INSERT INTO source (provider, board, country) VALUES ('careerpage', 'https://acme.test/kariera', 'SK') "
                        + "RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id, role) VALUES (?, ?, 'EMPLOYER')",
                company, source);
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, work_countries)
                VALUES ('ACTIVE', 'DEVOPS manager (m/ž)', 'https://acme.test/kariera/devops', now(), now(), '{SK}')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at)
                VALUES (?, ?, 'devops', 'DEVOPS manager (m/ž)', 'https://acme.test/kariera/devops', now(), now())
                """, source, vacancy);

        runChain();

        assertThat(jdbcTemplate.queryForObject("SELECT company_id FROM profesia_offer WHERE num = 101", Long.class))
                .isEqualTo(company);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM job_posting WHERE external_id LIKE 'profesia:%'",
                Integer.class)).isZero();
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
