package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.adapter.stateportal.StatePortalSearch;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Общий список вакансий портала (стенограмма §105) на заглушке: полный обход страницами по две записи, по две
 * страницы за задание; IČO — по списку работодателей портала, поиском работодателей по названию, со страницы своей
 * вакансии; объявление площадки без IČO записывается без него; по итогу обхода — подключение работодателей и записи в
 * источники; лента «новые сверху» — неполные записи новых вакансий. Даты размещения со страниц объявлений здесь
 * выключены ({@code dates-per-record=0}): объявления площадки — с настоящими адресами; даты проверяет
 * {@code StatePortalAdapterTests}.
 */
@SpringBootTest(properties = {"app.http.allow-private-addresses=true", "app.state-portal.list.page-size=2",
        "app.state-portal.list.pages-per-task=2", "app.state-portal.dates-per-record=0"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PortalListTests {

    private static final String LISTED = "Listed s. r. o.";
    private static final String LISTED_NUMBER = "90000011";
    private static final String SEARCHED = "Searched a. s.";
    private static final String DETAIL = "Detail spol.";
    private static final String NOBODY = "Nobody";
    /** Работодатель {@link #SEARCHED} — кадровое агентство: источник портала не подключается. */
    private static final String AGENCY_NUMBER = "90000012";
    /** Работодатель {@link #DETAIL} — со своей кадровой страницей: источник портала не подключается. */
    private static final String OWN_PAGE_NUMBER = "00000123";
    private static final String OWN_PAGE_BOARD = "own-page-list-test";
    private static final String PORTAL_SOURCES = "SELECT id FROM source WHERE provider = 'sluzbyzamestnanosti'";
    /** {@link #UUID_E} пропала из списка портала. */
    private static volatile boolean withoutE;
    /** Страница полного обхода, на которую портал отвечает 500; 0 — нет такой. */
    private static volatile int failingPage;
    private static final String UUID_A = "0a0a0a0a-1111-4111-8111-000000000001";
    private static final String UUID_C = "0a0a0a0a-1111-4111-8111-000000000003";
    private static final String UUID_E = "0a0a0a0a-1111-4111-8111-000000000005";
    private static final String UUID_F = "0a0a0a0a-1111-4111-8111-000000000006";
    /**
     * Полный обход «старые сверху»: страницы по две записи — 2, 2, 2, 0; на второй странице запись без работодателя
     * пропускается — страница с одной годной вакансией обход не завершает.
     */
    private static final List<String> OLDEST = List.of(
            own(UUID_A, "Účtovník", LISTED),
            external("https://www.profesia.sk/praca/searched/O123456", "Skladník", SEARCHED),
            "{\"uuid\":\"0a0a0a0a-1111-4111-8111-000000000009\",\"nazovPracovnehoMiesta\":\"Bez firmy\","
                    + "\"priznakZdroj\":1}",
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
    private StatePortalSearch search;

    @Autowired
    private PortalListRepository repository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private SourceRepository sources;

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
        withoutE = false;
        failingPage = 0;
        cleanUpSources();
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        for (String number : List.of(LISTED_NUMBER, AGENCY_NUMBER, OWN_PAGE_NUMBER)) {
            jdbcTemplate.update("""
                    INSERT INTO company (country, registration_number, name, registry, agency)
                    VALUES ('SK', ?, ?, 'RPO', ?)
                    ON CONFLICT (country, registration_number) DO UPDATE SET agency = EXCLUDED.agency,
                                                                             terminated_on = NULL
                    """, number, "Company " + number, AGENCY_NUMBER.equals(number));
        }
        jdbcTemplate.update("INSERT INTO source (provider, board, country) VALUES ('greenhouse', ?, 'SK')",
                OWN_PAGE_BOARD);
        jdbcTemplate.update("""
                INSERT INTO company_source (company_id, source_id, role)
                SELECT c.id, s.id, 'EMPLOYER' FROM company c, source s
                WHERE c.registration_number = ? AND s.board = ?
                """, OWN_PAGE_NUMBER, OWN_PAGE_BOARD);
    }

    /**
     * Источники портала и их вакансии, связи компаний теста — не остаются другим тестам (внешние ключи источника).
     */
    @AfterEach
    void cleanUpSources() {
        jdbcTemplate.update("DELETE FROM vacancy_revision WHERE job_posting_id IN (SELECT id FROM job_posting "
                + "WHERE source_id IN (" + PORTAL_SOURCES + "))");
        jdbcTemplate.update("DELETE FROM crawl_run WHERE source_id IN (" + PORTAL_SOURCES + ")");
        jdbcTemplate.update("WITH gone AS (DELETE FROM job_posting WHERE source_id IN (" + PORTAL_SOURCES
                + ") RETURNING vacancy_id) DELETE FROM vacancy WHERE id IN (SELECT vacancy_id FROM gone)");
        String companies = "SELECT id FROM company WHERE registration_number IN ('" + LISTED_NUMBER + "', '"
                + AGENCY_NUMBER + "', '" + OWN_PAGE_NUMBER + "')";
        jdbcTemplate.update("DELETE FROM company_check WHERE company_id IN (" + companies + ")");
        jdbcTemplate.update("DELETE FROM company_source WHERE company_id IN (" + companies + ")");
        jdbcTemplate.update("DELETE FROM source WHERE provider = 'sluzbyzamestnanosti' OR board = ?", OWN_PAGE_BOARD);
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
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM portal_cycle WHERE completed_at IS NOT NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE task_key LIKE 'portal-list:cycle:%'",
                Integer.class)).isEqualTo(2);
        assertThat(handler.startCycleIfDue()).isFalse();
    }

    /**
     * Завершённый обход: источник портала подключён работодателю из реестра ({@link #LISTED}), но не агентству и не
     * компании со своей кадровой страницей; его вакансии записаны полным чтением (§107).
     */
    @Test
    void completedCycleConnectsEmployersAndRecordsFullRead() {
        handler.startCycleIfDue();
        runQueuedTasks();

        assertThat(jdbcTemplate.queryForList("SELECT s.board || ':' || c.registration_number FROM company_source cs "
                + "JOIN source s ON s.id = cs.source_id JOIN company c ON c.id = cs.company_id "
                + "WHERE s.provider = 'sluzbyzamestnanosti'", String.class))
                .containsExactly(LISTED_NUMBER + ":" + LISTED_NUMBER);
        assertThat(postings()).containsExactlyInAnyOrder(UUID_A + ":ACTIVE", UUID_E + ":ACTIVE");
        assertThat(jdbcTemplate.queryForList("SELECT state FROM crawl_run WHERE source_id IN (" + PORTAL_SOURCES + ")",
                String.class)).containsExactly("COMPLETE");
    }

    /**
     * Вакансия, которой нет в трёх полных обходах подряд, закрыта; остальные — актуальны (§107).
     */
    @Test
    void offerMissingFromThreeCyclesIsClosed() {
        handler.startCycleIfDue();
        runQueuedTasks();
        withoutE = true;

        for (int cycle = 0; cycle < 3; cycle++) {
            assertThat(postings()).containsExactlyInAnyOrder(UUID_A + ":ACTIVE",
                    UUID_E + (cycle == 0 ? ":ACTIVE" : ":NEEDS_RECHECK"));
            jdbcTemplate.update("UPDATE portal_cycle SET started_at = started_at - interval '1 day'");
            assertThat(handler.startCycleIfDue()).isTrue();
            runQueuedTasks();
        }

        assertThat(postings()).containsExactlyInAnyOrder(UUID_A + ":ACTIVE", UUID_E + ":CLOSED");
    }

    /**
     * Лента после обхода: новая вакансия работодателя с источником портала записана сразу неполным чтением
     * ({@code NEW_OFFERS}) — отсутствие других не засчитывается (счётчик пропусков 0); следующая лента ту же, уже
     * записанную вакансию не пишет снова (§107, §109).
     */
    @Test
    void feedRecordsNewOffersAsPartialRead() {
        handler.startCycleIfDue();
        runQueuedTasks();

        handler.enqueueFeed("2026-10-14T12");
        runQueuedTasks();
        handler.enqueueFeed("2026-10-14T13");
        runQueuedTasks();

        assertThat(jdbcTemplate.queryForList("SELECT external_id || ':' || missing_complete_reads FROM job_posting "
                + "WHERE source_id IN (" + PORTAL_SOURCES + ")", String.class))
                .containsExactlyInAnyOrder(UUID_A + ":0", UUID_E + ":0", UUID_F + ":0");
        assertThat(jdbcTemplate.queryForList("SELECT state || ':' || coalesce(partial_reason, '-') FROM crawl_run "
                + "WHERE source_id IN (" + PORTAL_SOURCES + ") ORDER BY id", String.class))
                .containsExactly("COMPLETE:-", "PARTIAL:NEW_OFFERS");
    }

    /**
     * Лента не ставит запись источнику, у которого запись ещё в очереди: записи одного источника не идут
     * параллельно, новая вакансия войдёт в идущую запись (§107).
     */
    @Test
    void feedSkipsSourceWithPendingRecord() {
        handler.startCycleIfDue();
        runQueuedTasks();
        long sourceId = jdbcTemplate.queryForObject(PORTAL_SOURCES, Long.class);
        taskService.enqueue(PortalRecordHandler.TYPE, "portal-record:" + sourceId + ":c999",
                PortalRecordHandler.payload(sourceId, 1, null));
        handler.enqueueFeed("2026-10-14T12");

        executor.execute(jdbcTemplate.queryForObject("SELECT id FROM task WHERE task_key = ?", Long.class,
                "portal-list:feed:2026-10-14T12"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE task_key LIKE 'portal-record:%:f%'",
                Integer.class)).isZero();
    }

    /**
     * Страницу обхода портал стабильно не отдаёт: после трёх неудачных попыток задания она пропускается, обход идёт дальше и
     * завершается неполным — запись источника неполная ({@code PAGE_FAILED}), отсутствие не засчитывается (§109).
     */
    @Test
    void stablyFailingPageIsSkippedAndCycleIsPartial() {
        failingPage = 2;
        long cycle = repository.startCycle();

        TaskOutcome outcome = handler.handle(new TaskRecord(0, PortalListHandler.TYPE,
                "{\"mode\": \"cycle\", \"cycle\": " + cycle + ", \"page\": 2}",
                PortalListHandler.SKIP_PAGE_AFTER_ATTEMPTS));
        TaskOutcome.Continue next = (TaskOutcome.Continue) outcome;
        taskService.enqueue(PortalListHandler.TYPE, next.taskKey(), next.payload());
        runQueuedTasks();

        assertThat(jdbcTemplate.queryForObject("SELECT skipped_pages || ':' || (completed_at IS NOT NULL) "
                + "FROM portal_cycle", String.class)).isEqualTo("1:true");
        assertThat(jdbcTemplate.queryForList("SELECT state || ':' || coalesce(partial_reason, '-') FROM crawl_run "
                + "WHERE source_id IN (" + PORTAL_SOURCES + ")", String.class)).containsExactly("PARTIAL:PAGE_FAILED");
    }

    private List<String> postings() {
        return jdbcTemplate.queryForList("SELECT p.external_id || ':' || v.state FROM job_posting p "
                + "JOIN vacancy v ON v.id = p.vacancy_id WHERE p.source_id IN (" + PORTAL_SOURCES + ")", String.class);
    }

    /**
     * Срок задания вышел (0 с): определяется IČO одной вакансии, найденное записано, та же страница — продолжением в
     * следующем часу с другим ключом; поиска работодателей по названию в этом задании нет (§106).
     */
    @Test
    void outOfTimeTaskStopsSearchingAndContinuesSamePage() {
        PortalListHandler noTime = new PortalListHandler(search, repository, sources, taskService,
                new PortalListProperties(2, 2, Duration.ofHours(20), Duration.ZERO, Duration.ofDays(7)));
        long cycle = repository.startCycle();

        TaskOutcome outcome = noTime.handle(new TaskRecord(0, PortalListHandler.TYPE,
                "{\"mode\": \"cycle\", \"cycle\": " + cycle + ", \"page\": 1}", 0));

        assertThat(NAME_SEARCHES.get()).isZero();
        assertThat(offers()).containsExactlyInAnyOrder(UUID_A + "|" + LISTED_NUMBER + "|Účtovník (muž/žena)",
                "profesia:123456|-|Skladník (muž/žena)");
        assertThat(outcome).isInstanceOfSatisfying(TaskOutcome.Continue.class,
                next -> assertThat(next.taskKey()).isEqualTo("portal-list:cycle:" + cycle + ":1.1"));
    }

    /**
     * Продолжение страницы начинает с вакансии, на которой остановилось прошлое задание: вакансия без IČO в начале
     * страницы не держит страницу — третья страница (объявление площадки без IČO, затем вакансия {@link #LISTED})
     * проходит за два задания со сроком 0, дальше — четвёртая страница (§106).
     */
    @Test
    void continuationStartsWhereThePreviousPartStopped() {
        PortalListHandler noTime = new PortalListHandler(search, repository, sources, taskService,
                new PortalListProperties(2, 2, Duration.ofHours(20), Duration.ZERO, Duration.ofDays(7)));
        long cycle = repository.startCycle();

        TaskOutcome first = noTime.handle(new TaskRecord(0, PortalListHandler.TYPE,
                "{\"mode\": \"cycle\", \"cycle\": " + cycle + ", \"page\": 3}", 0));
        TaskOutcome second = noTime.handle(new TaskRecord(0, PortalListHandler.TYPE,
                "{\"mode\": \"cycle\", \"cycle\": " + cycle + ", \"page\": 3, \"from\": 1}", 0));

        assertThat(offers()).containsExactlyInAnyOrder("https://www.worki.sk/ponuka/1|-|Kuchár (muž/žena)",
                UUID_E + "|" + LISTED_NUMBER + "|Predavač (muž/žena)");
        assertThat(List.of(first, second)).extracting(outcome -> ((TaskOutcome.Continue) outcome).taskKey())
                .containsExactly("portal-list:cycle:" + cycle + ":3.1", "portal-list:cycle:" + cycle + ":4");
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
     * Лента «новые сверху» до первого обхода записывает вакансию в общий список (в источники — нечего: обходов не
     * было); повтор в тот же час ничего не ставит. Название с пометкой «žena/muž» — без второй пометки (Unicode-границы
     * слов, §108).
     */
    @Test
    void feedAddsNewestOffersWithoutCycle() {
        assertThat(handler.enqueueFeed("2026-10-14T10")).isTrue();
        assertThat(handler.enqueueFeed("2026-10-14T10")).isFalse();
        runQueuedTasks();

        assertThat(offers()).containsExactly(UUID_F + "|" + LISTED_NUMBER + "|Pokladník žena/muž");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE type = ?", Integer.class,
                PortalRecordHandler.TYPE)).isZero();
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
                    records = List.of(own(UUID_F, "Pokladník žena/muž", LISTED));
                } else {
                    int size = Integer.parseInt(query.replaceAll(".*pageSize=(\\d+).*", "$1"));
                    int page = Integer.parseInt(query.replaceAll(".*pageNr=(\\d+).*", "$1"));
                    if (page == failingPage) {
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }
                    List<String> oldest = withoutE ? OLDEST.subList(0, OLDEST.size() - 1) : OLDEST;
                    int from = Math.min((page - 1) * size, oldest.size());
                    records = oldest.subList(from, Math.min(from + size, oldest.size()));
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
