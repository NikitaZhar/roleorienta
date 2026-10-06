package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.source.SourceRepository;
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
 * Государственный портал на заглушке и настоящей PostgreSQL (бизнес-описание §4.1): список
 * работодателей; подключение источника портала только работодателю из реестра, не агентству и без своей
 * кадровой страницы; у компании со своей кадровой страницей источник портала не читается.
 */
@SpringBootTest(properties = "app.http.allow-private-addresses=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StatePortalTests {

    /** С вакансиями, в реестре — подключается. */
    private static final String EMPLOYER = "90000001";
    /** С вакансиями, кадровое агентство — не подключается. */
    private static final String AGENCY = "90000002";
    /** С вакансиями, есть своя кадровая страница — не проверяется. */
    private static final String OWN_PAGE = "90000003";
    /** Без вакансий — проверен, не подключается. */
    private static final String NO_OFFERS = "90000004";
    /** С вакансиями, нет в реестре — не проверяется. */
    private static final String NOT_REGISTERED = "90000005";
    private static final List<String> NUMBERS = List.of(EMPLOYER, AGENCY, OWN_PAGE, NO_OFFERS, NOT_REGISTERED);
    private static final int MAX_TASK_ROUNDS = 10;
    /** Своя вакансия портала у {@link #EMPLOYER}; в её детали — почта на домене {@link #EMPLOYER_HOST}. */
    private static final String OFFER_UUID = "0b8c1f2e-3a4d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String EMPLOYER_HOST = "employer-test.sk";
    /** Сайт по домену почты {@link #EMPLOYER_HOST}: IČO работодателя — на странице «Kontakt». */
    private static final String MAIL_SITE = "www." + EMPLOYER_HOST;
    /** «Internetová adresa» в детали вакансии; {@code null} — поля нет (сайт берётся по почте). */
    private static volatile String website;
    private static final Map<String, Integer> OFFERS = Map.of(EMPLOYER, 2, AGENCY, 1, OWN_PAGE, 3,
            NOT_REGISTERED, 1);
    private static final HttpServer PORTAL = startPortal();

    @Autowired
    private StatePortalHandler handler;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private SourceRepository sources;

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
        registry.add("app.site-check.home-url",
                () -> "http://127.0.0.1:" + PORTAL.getAddress().getPort() + "/site/{host}/");
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopPortal() {
        PORTAL.stop(0);
    }

    /**
     * Чистые записи этого теста; Словакия — активная страна сбора; в реестре — работодатель, агентство,
     * компания со своей кадровой страницей (источник Greenhouse) и компания без вакансий.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM portal_employer");
        website = null;
        String numbers = "'" + String.join("','", NUMBERS) + "'";
        jdbcTemplate.update("DELETE FROM company_check WHERE company_id IN (SELECT id FROM company WHERE "
                + "registration_number IN (" + numbers + "))");
        jdbcTemplate.update("DELETE FROM company_source WHERE company_id IN (SELECT id FROM company WHERE "
                + "registration_number IN (" + numbers + "))");
        jdbcTemplate.update("DELETE FROM company_site WHERE company_id IN (SELECT id FROM company WHERE "
                + "registration_number IN (" + numbers + "))");
        jdbcTemplate.update("DELETE FROM source WHERE provider = ? OR board = 'own-page-test'", StatePortalAdapter.PROVIDER);
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        for (String number : List.of(EMPLOYER, AGENCY, OWN_PAGE, NO_OFFERS)) {
            jdbcTemplate.update("""
                    INSERT INTO company (country, registration_number, name, registry, agency)
                    VALUES ('SK', ?, ?, 'RPO', ?)
                    ON CONFLICT (country, registration_number) DO UPDATE SET agency = EXCLUDED.agency,
                                                                             terminated_on = NULL
                    """, number, "Company " + number, AGENCY.equals(number));
        }
        jdbcTemplate.update("INSERT INTO source (provider, board, country) VALUES ('greenhouse', 'own-page-test', 'SK')");
        jdbcTemplate.update("""
                INSERT INTO company_source (company_id, source_id, role)
                SELECT c.id, s.id, 'EMPLOYER' FROM company c, source s
                WHERE c.registration_number = ? AND s.board = 'own-page-test'
                """, OWN_PAGE);
    }

    /**
     * Список работодателей записан целиком; источник портала (доска — IČO) подключён только работодателю
     * из реестра с вакансиями, не агентству и без своей кадровой страницы; итог компании — «подключена».
     */
    @Test
    void connectsRegisteredEmployersWithOffers() {
        handler.enqueueList("test");
        runQueuedTasks();
        handler.enqueueCheck("test");
        runQueuedTasks();

        assertThat(jdbcTemplate.queryForList("SELECT registration_number FROM portal_employer ORDER BY 1", String.class))
                .containsExactlyElementsOf(NUMBERS);
        assertThat(jdbcTemplate.queryForList("""
                SELECT registration_number || ':' || has_offers FROM portal_employer
                WHERE checked_at IS NOT NULL ORDER BY 1
                """, String.class)).containsExactly(EMPLOYER + ":true", NO_OFFERS + ":false");
        assertThat(jdbcTemplate.queryForList("""
                SELECT c.registration_number || ':' || s.board || ':' || cs.role || ':' || ch.result
                FROM company_source cs JOIN source s ON s.id = cs.source_id JOIN company c ON c.id = cs.company_id
                JOIN company_check ch ON ch.company_id = c.id
                WHERE s.provider = ?
                """, String.class, StatePortalAdapter.PROVIDER)).containsExactly(
                EMPLOYER + ":" + EMPLOYER + ":EMPLOYER:CONNECTED");
    }

    /**
     * Сайт с портала по домену почты контакта: у подключённого работодателя без сайта открывается
     * {@code www.<домен>}, IČO работодателя — на странице «Kontakt» по ссылке с главной: сайт записан с
     * подтверждением {@code PORTAL_MAIL}; работодателя с найденным сайтом второй раз не ищут.
     */
    @Test
    void takesEmployerSiteFromPortalMail() {
        runSiteStep();

        assertThat(employerSites()).containsExactly(EMPLOYER + ":" + MAIL_SITE + ":null:STATE_PORTAL:PORTAL_MAIL:"
                + "http://127.0.0.1:" + PORTAL.getAddress().getPort() + "/pracovne-ponuky/" + OFFER_UUID);
        assertThat(jdbcTemplate.queryForObject("SELECT site_checked_at IS NOT NULL FROM portal_employer "
                + "WHERE registration_number = ?", Boolean.class, EMPLOYER)).isTrue();
    }

    /**
     * «Internetová adresa» с путём — сайт без проверки ({@code STATE_PORTAL}), адрес с путём — стартовая
     * страница поиска кадровой страницы.
     */
    @Test
    void takesEmployerWebsiteWithPath() {
        website = "www.employer-test.sk/sk/";

        runSiteStep();

        assertThat(employerSites()).containsExactly(EMPLOYER + ":" + MAIL_SITE + ":https://www.employer-test.sk/sk/"
                + ":STATE_PORTAL:STATE_PORTAL:http://127.0.0.1:" + PORTAL.getAddress().getPort()
                + "/pracovne-ponuky/" + OFFER_UUID);
    }

    private void runSiteStep() {
        handler.enqueueList("test");
        runQueuedTasks();
        handler.enqueueCheck("test");
        runQueuedTasks();
        handler.enqueueSite("test");
        runQueuedTasks();
    }

    private List<String> employerSites() {
        return jdbcTemplate.queryForList("""
                SELECT c.registration_number || ':' || s.host || ':' || coalesce(s.start_url, 'null') || ':'
                       || s.source || ':' || s.proof || ':' || s.evidence_url
                FROM company_site s JOIN company c ON c.id = s.company_id
                WHERE c.registration_number IN (?, ?) AND s.status = 'FOUND'
                """, String.class, EMPLOYER, OWN_PAGE);
    }

    /**
     * Один канал на компанию: источник портала компании со своей кадровой страницей к чтению не ставится,
     * источник работодателя без своей страницы — ставится.
     */
    @Test
    void portalSourceIsNotReadWhenCompanyHasOwnPage() {
        for (String number : List.of(EMPLOYER, OWN_PAGE)) {
            jdbcTemplate.update("INSERT INTO source (provider, board, country) VALUES (?, ?, 'SK')",
                    StatePortalAdapter.PROVIDER, number);
            jdbcTemplate.update("""
                    INSERT INTO company_source (company_id, source_id, role)
                    SELECT c.id, s.id, 'EMPLOYER' FROM company c, source s
                    WHERE c.registration_number = ? AND s.provider = ? AND s.board = ?
                    """, number, StatePortalAdapter.PROVIDER, number);
        }

        List<String> toRead = sources.findToRead(StatePortalAdapter.PROVIDER).stream()
                .map(source -> source.getProvider() + ":" + source.getBoard()).toList();

        assertThat(toRead).contains(StatePortalAdapter.PROVIDER + ":" + EMPLOYER, "greenhouse:own-page-test")
                .doesNotContain(StatePortalAdapter.PROVIDER + ":" + OWN_PAGE);
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
     * Заглушка портала: список работодателей — одна страница со всеми IČO; вакансии работодателя —
     * объявленное число из {@link #OFFERS} (нет — ноль); сайт по домену почты — {@code /site/<хост>/}.
     */
    private static HttpServer startPortal() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/zamestnavatelia", exchange -> {
                StringBuilder body = new StringBuilder("<html><body><main>");
                if (exchange.getRequestURI().getQuery().contains("pageNr=1")) {
                    for (String number : NUMBERS) {
                        body.append("<div><h3 class=\"govuk-signpost__title\">Company ").append(number)
                                .append("</h3><p class=\"govuk-signpost__description\">IČO: ").append(number)
                                .append("</p></div>");
                    }
                }
                respond(exchange, body.append("</main></body></html>").toString());
            });
            server.createContext("/pracovne-ponuky", exchange -> {
                if (exchange.getRequestURI().getPath().endsWith(OFFER_UUID)) {
                    respond(exchange, "<html><body><dl><div><dt class=\"vpm-data-panel__key\">IČO</dt>"
                            + "<dd class=\"vpm-data-panel__value\">" + EMPLOYER + "</dd></div><div><dt class=\"vpm-data-panel__key\">"
                            + "Názov spoločnosti</dt><dd>Employer Test s. r. o.</dd></div>"
                            + (website == null ? "" : "<div><dt class=\"vpm-data-panel__key\">Internetová adresa</dt>"
                                    + "<dd>" + website + "</dd></div>") + "</dl>"
                            + "<script> { let name = \"hr\"; let host = \"" + EMPLOYER_HOST + "\"; } </script>"
                            + "</body></html>");
                    return;
                }
                String query = exchange.getRequestURI().getQuery();
                String number = query.replaceAll(".*firma=(\\d{8}).*", "$1");
                String offer = EMPLOYER.equals(number) ? "<a href=\"/pracovne-ponuky/" + OFFER_UUID + "\">"
                        + "<h3 class=\"govuk-signpost__title\">Účtovník</h3></a>" : "";
                respond(exchange, "<html><body><main><span>" + OFFERS.getOrDefault(number, 0)
                        + " pracovných ponúk,</span>" + offer + "</main></body></html>");
            });
            server.createContext("/site/" + MAIL_SITE + "/", exchange -> respond(exchange,
                    exchange.getRequestURI().getPath().endsWith("/kontakt")
                            ? "<html><body><p>Employer Test s. r. o., IČO: " + EMPLOYER + "</p></body></html>"
                            : "<html><body><a href=\"kontakt\">Kontakt</a></body></html>"));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
