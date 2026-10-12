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
 * Шаг «список работодателей profesia.sk» (§83) на заглушке profesia.sk и настоящей PostgreSQL: карта сайта →
 * работодатели, сопоставленные компаниям реестра по названию (только однозначно) → объявление → страница компании и
 * раздел {@code cd_page} → ссылка на систему найма записана сайтом компании (источник и подтверждение
 * {@code PROFESIA}).
 */
@SpringBootTest(properties = "app.http.allow-private-addresses=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProfesiaListTests {

    private static final String GAMA = "90500011";
    private static final String DELTA_1 = "90500012";
    private static final String DELTA_2 = "90500013";
    private static final Map<String, String> PAGES = new ConcurrentHashMap<>();
    private static final HttpServer PROFESIA = startProfesia();
    private static final String BASE = "http://127.0.0.1:" + PROFESIA.getAddress().getPort();

    @Autowired
    private ProfesiaListHandler handler;

    @Autowired
    private TaskExecutor executor;

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
     * Компании реестра: «Gama Slovakia, k.s.» — однозначно; две «Delta» — неоднозначно.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM profesia_employer");
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        company(GAMA, "Gama Slovakia, k.s.");
        company(DELTA_1, "Delta s.r.o.");
        company(DELTA_2, "DELTA, a. s.");
        jdbcTemplate.update("DELETE FROM company_site WHERE company_id IN (SELECT id FROM company WHERE "
                + "registration_number IN (?, ?, ?))", GAMA, DELTA_1, DELTA_2);
        PAGES.clear();
        PAGES.put("/sitemap.php", "<urlset><url><loc>" + BASE + "/praca/bratislava/</loc></url>"
                + "<url><loc>" + BASE + "/praca/gama-slovakia-k-s/O7</loc></url>"
                + "<url><loc>" + BASE + "/praca/gama-slovakia-k-s/O8</loc></url>"
                + "<url><loc>" + BASE + "/praca/delta/O9</loc></url></urlset>");
        PAGES.put("/praca/gama-slovakia-k-s/O7", "<a href=\"/praca/gama-slovakia-k-s/C77\">Gama Slovakia</a>");
        PAGES.put("/praca/gama-slovakia-k-s/C77", "<a href=\"/praca/gama-slovakia-k-s/C77?cd_page=o-nas\">O nás</a>"
                + "<a href=\"https://www.gama-test.sk/\">Web</a>");
        PAGES.put("/praca/delta/O9", "<a href=\"/praca/delta/C99\">Delta</a>");
    }

    /**
     * Работодатель карты сайта сопоставлен однозначно — ссылка на систему найма из раздела {@code cd_page} записана
     * сайтом компании; неоднозначный — без компании, его объявление не открывается.
     */
    @Test
    void recordsCareerLinkOfListedEmployer() {
        PAGES.put("/praca/gama-slovakia-k-s/C77?cd_page=o-nas",
                "<a href=\"https://gama.wd3.myworkdayjobs.com/External\">Voľné miesta</a>");

        runChain();

        assertThat(jdbcTemplate.queryForList("""
                SELECT e.slug || ':' || coalesce(c.registration_number, 'null') || ':' || (e.checked_at IS NOT NULL)
                FROM profesia_employer e LEFT JOIN company c ON c.id = e.company_id ORDER BY 1
                """, String.class)).containsExactly("delta:null:false", "gama-slovakia:" + GAMA + ":true");
        assertThat(sites(GAMA)).containsExactly(
                "gama.wd3.myworkdayjobs.com:https://gama.wd3.myworkdayjobs.com/External:PROFESIA:PROFESIA:FOUND");
        assertThat(sites(DELTA_1)).isEmpty();
    }

    /**
     * Сайт со страницы profesia.sk у компании уже есть — работодатель не берётся.
     */
    @Test
    void skipsCompanyWithProfesiaSite() {
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, proof)
                SELECT id, 'www.gama-test.sk', 'https://www.profesia.sk/praca/gama/C77', 'STATE_PORTAL', 'PROFESIA'
                FROM company WHERE registration_number = ?
                """, GAMA);

        runChain();

        assertThat(jdbcTemplate.queryForObject("SELECT checked_at IS NULL FROM profesia_employer "
                + "WHERE slug = 'gama-slovakia'", Boolean.class)).isTrue();
        assertThat(sites(GAMA)).containsExactly("www.gama-test.sk:null:STATE_PORTAL:PROFESIA:FOUND");
    }

    /**
     * Разбор карты сайта и название компании в виде адреса: правовая форма в конце отброшена, диакритика снята.
     */
    @Test
    void matchesNamesLikeProfesiaAddresses() {
        assertThat(ProfesiaListHandler.employers(PAGES.get("/sitemap.php"))).containsExactly(
                Map.entry("gama-slovakia", BASE + "/praca/gama-slovakia-k-s/O7"),
                Map.entry("delta", BASE + "/praca/delta/O9"));
        assertThat(ProfesiaListHandler.slug("hagard-hal-spol-s-r-o")).isEqualTo("hagard-hal");
        company("90500014", "Žilinská Teplárenská (SK), spol. s r. o.");
        assertThat(jdbcTemplate.queryForObject("SELECT name_slug FROM company WHERE registration_number = ?",
                String.class, "90500014")).isEqualTo("zilinska-teplarenska-sk");
    }

    private void company(String ico, String name) {
        jdbcTemplate.update("""
                INSERT INTO company (country, registration_number, name, registry) VALUES ('SK', ?, ?, 'RPO')
                ON CONFLICT (country, registration_number) DO UPDATE SET name = EXCLUDED.name, terminated_on = NULL
                """, ico, name);
    }

    private void runChain() {
        handler.enqueue("test");
        for (int pass = 0; pass < 5; pass++) {
            List<Long> queued = jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id",
                    Long.class);
            queued.forEach(executor::execute);
        }
    }

    private List<String> sites(String ico) {
        return jdbcTemplate.queryForList("""
                SELECT s.host || ':' || coalesce(s.start_url, 'null') || ':' || s.source || ':' || s.proof || ':'
                       || s.status
                FROM company_site s JOIN company c ON c.id = s.company_id WHERE c.registration_number = ? ORDER BY 1
                """, String.class, ico);
    }

    /**
     * Заглушка profesia.sk: путь с запросом → страница из {@link #PAGES}; нет — 404.
     */
    private static HttpServer startProfesia() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                String page = PAGES.get(exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query));
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
