package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.task.TaskExecutor;
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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Шаг «страница компании на profesia.sk» (§82) на заглушке profesia.sk и настоящей PostgreSQL: объявление profesia.sk
 * у источника портала → страница компании и её раздел → ссылка на систему найма записана сайтом компании со стартовой
 * страницей; без кадровой ссылки — первая ссылка (сайт компании); ссылки подвала profesia.sk и соцсетей не берутся.
 */
@SpringBootTest(properties = "app.http.allow-private-addresses=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProfesiaSiteTests {

    private static final String ICO = "90500001";
    private static final String MARK = "profesia-site-test";
    private static final Map<String, String> PAGES = new ConcurrentHashMap<>();
    private static final HttpServer PROFESIA = startProfesia();
    private static final String BASE = "http://127.0.0.1:" + PROFESIA.getAddress().getPort();

    @Autowired
    private ProfesiaSiteHandler handler;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopProfesia() {
        PROFESIA.stop(0);
    }

    /**
     * Работодатель портала в реестре, с источником портала и объявлением profesia.sk (адрес — заглушка).
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM job_posting WHERE title = ?", MARK);
        jdbcTemplate.update("DELETE FROM vacancy WHERE title = ?", MARK);
        jdbcTemplate.update("DELETE FROM company_source WHERE source_id IN (SELECT id FROM source WHERE board = ?)", ICO);
        jdbcTemplate.update("DELETE FROM source WHERE board = ?", ICO);
        jdbcTemplate.update("DELETE FROM company_check WHERE company_id IN (SELECT id FROM company WHERE "
                + "registration_number = ?)", ICO);
        jdbcTemplate.update("DELETE FROM company_site WHERE company_id IN (SELECT id FROM company WHERE "
                + "registration_number = ?)", ICO);
        jdbcTemplate.update("DELETE FROM portal_employer WHERE registration_number = ?", ICO);
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        jdbcTemplate.update("""
                INSERT INTO company (country, registration_number, name, registry) VALUES ('SK', ?, 'Alfa s.r.o.', 'RPO')
                ON CONFLICT (country, registration_number) DO UPDATE SET terminated_on = NULL
                """, ICO);
        jdbcTemplate.update("INSERT INTO portal_employer (registration_number, name) VALUES (?, 'Alfa s.r.o.')", ICO);
        jdbcTemplate.update("UPDATE portal_employer SET profesia_checked_at = now() WHERE registration_number <> ?",
                ICO);
        jdbcTemplate.update("INSERT INTO source (provider, board, country) VALUES ('sluzbyzamestnanosti', ?, 'SK')",
                ICO);
        jdbcTemplate.update("""
                INSERT INTO company_source (company_id, source_id, role)
                SELECT c.id, s.id, 'EMPLOYER' FROM company c, source s WHERE c.registration_number = ? AND s.board = ?
                """, ICO, ICO);
        jdbcTemplate.update("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', ?, ?, now(), now())
                """, MARK, BASE + "/O1");
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at)
                SELECT s.id, v.id, 'profesia:1', ?, ?, now(), now() FROM source s, vacancy v
                WHERE s.board = ? AND v.title = ?
                """, MARK, BASE + "/O1", ICO, MARK);
        PAGES.clear();
        PAGES.put("/O1", "<a href=\"/praca/alfa/C11\">Alfa s.r.o.</a>"
                + "<section id=\"profesia-footer\"><a href=\"https://www.jobs.cz/\">Jobs.cz</a></section>");
    }

    /**
     * Ссылка на систему найма — в разделе страницы компании (как «Open positions» у Henkel): записана сайтом компании
     * со стартовой страницей — этой ссылкой; сайт компании из той же страницы проиграл кадровой ссылке.
     */
    @Test
    void recordsCareerLinkFromCompanySection() {
        PAGES.put("/praca/alfa/C11", "<a href=\"/praca/alfa-sk/C11?page_num=2002\">Open positions</a>"
                + "<a href=\"https://www.alfa-test.sk/\">Web</a><a href=\"https://www.facebook.com/alfa\">Facebook</a>");
        PAGES.put("/praca/alfa-sk/C11", "<a href=\"https://alfa.wd3.myworkdayjobs.com/External\">All positions</a>");

        runTask();

        assertThat(sites()).containsExactly(
                "alfa.wd3.myworkdayjobs.com:https://alfa.wd3.myworkdayjobs.com/External:STATE_PORTAL:PROFESIA:FOUND");
        assertThat(jdbcTemplate.queryForObject("SELECT profesia_checked_at IS NOT NULL FROM portal_employer "
                + "WHERE registration_number = ?", Boolean.class, ICO)).isTrue();
    }

    /**
     * Кадровой ссылки нет — первая ссылка компании (сайт); тот же хост был кандидатом — становится найденным.
     */
    @Test
    void recordsCompanySiteAndConfirmsCandidate() {
        PAGES.put("/praca/alfa/C11", "<a href=\"https://www.facebook.com/alfa\">Facebook</a>"
                + "<a href=\"https://www.alfa-test.sk/\">Web</a>");
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, proof, status, checked_at)
                SELECT id, 'www.alfa-test.sk', 'https://www.alfa-test.sk/', 'NAME', 'HTTP_403', 'CANDIDATE', now()
                FROM company WHERE registration_number = ?
                """, ICO);

        runTask();

        assertThat(sites()).containsExactly("www.alfa-test.sk:null:NAME:PROFESIA:FOUND");
        assertThat(jdbcTemplate.queryForObject("SELECT checked_at IS NULL FROM company_site s JOIN company c "
                + "ON c.id = s.company_id WHERE c.registration_number = ?", Boolean.class, ICO)).isTrue();
    }

    /**
     * Разбор: ссылка на страницу компании в объявлении; разделы — только той же компании; ссылки подвала profesia.sk и
     * соцсетей не берутся; кадровая ссылка выбирается раньше сайта.
     */
    @Test
    void parsesProfesiaPages() {
        Document ad = Jsoup.parse("<a href=\"/praca/beta/C22?x=1\">x</a><a href=\"/praca/beta/C22\">Beta</a>",
                "https://www.profesia.sk/O5");
        assertThat(ProfesiaCompanyPage.companyPage(ad)).contains("https://www.profesia.sk/praca/beta/C22");

        Document company = Jsoup.parse("""
                <a href="/praca/beta-sk/C22?page_num=2001">O nás</a><a href="/praca/gama/C33?page_num=2001">Iná</a>
                <a href="https://www.beta.sk/">Web</a><a href="https://www.beta.sk/kariera">Kariéra</a>
                <header><a href="https://www.cvonline.lt/">CV</a></header>
                """, "https://www.profesia.sk/praca/beta/C22");
        assertThat(ProfesiaCompanyPage.sections(company))
                .containsExactly("https://www.profesia.sk/praca/beta-sk/C22?page_num=2001");
        List<String> links = ProfesiaCompanyPage.companyLinks(company);
        assertThat(links).containsExactly("https://www.beta.sk/", "https://www.beta.sk/kariera");
        assertThat(ProfesiaCompanyPage.choose(links)).contains("https://www.beta.sk/kariera");
    }

    private void runTask() {
        handler.enqueue("test");
        jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id", Long.class)
                .forEach(executor::execute);
    }

    private List<String> sites() {
        return jdbcTemplate.queryForList("""
                SELECT s.host || ':' || coalesce(s.start_url, 'null') || ':' || s.source || ':' || s.proof || ':'
                       || s.status
                FROM company_site s JOIN company c ON c.id = s.company_id WHERE c.registration_number = ? ORDER BY 1
                """, String.class, ICO);
    }

    /**
     * Заглушка profesia.sk: путь → страница из {@link #PAGES}; нет — 404.
     */
    private static HttpServer startProfesia() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String page = PAGES.get(exchange.getRequestURI().getPath());
                byte[] bytes = page == null ? new byte[0]
                        : ("<html><body>" + page + "</body></html>").getBytes(StandardCharsets.UTF_8);
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
