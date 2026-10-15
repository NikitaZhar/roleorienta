package com.roleorienta.worker.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.adapter.jobposting.JobPostingAdapter;
import com.roleorienta.worker.collect.ReadSourceHandler;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Приёмочные сценарии подэтапа 1.2 (бизнес-описание §7.4-А, технический документ §14) на тестовом
 * сайте с разметкой {@code JobPosting} и заранее известным содержимым: сценарий меняет страницы
 * между обходами, результат сравнивается точно.
 *
 * <ul>
 *   <li>6 — закрытие по подтверждённому отсутствию, история сохранена. Явного признака закрытия
 *       поддерживаемые форматы не дают; уход из накопленного списка — с выдачей (1.5).</li>
 *   <li>7 — временный отказ и неполное чтение не закрывают вакансии, переводят их в «нуждается в
 *       повторной проверке»; после восстановления — без потерь и повторов.</li>
 *   <li>9 (сбор) — остановка приложения во время чтения не теряет задание и не даёт повторов.</li>
 *   <li>Окно по кругу (аудит §121): ссылок больше потолка — прочитанные подтверждаются, непрочитанные известные
 *       не меняются, пропавшая с кадровой страницы закрывается по общим правилам.</li>
 * </ul>
 *
 * <p>Потолок страниц {@code JobPosting} в этом тесте — {@value #WINDOW}: сценарии 6, 7, 9 укладываются в него.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SourceToVacancyAcceptanceTests {

    private static final String JOB_TEMPLATE = """
            <html><head><script type="application/ld+json">
            {"@context":"https://schema.org","@type":"JobPosting","title":"%s",
             "jobLocation":{"address":{"addressLocality":"Bratislava","addressCountry":"SK"}}}
            </script></head><body>Job</body></html>
            """;

    /** Страницы тестового сайта: путь → тело; пути из {@link #STATUSES} отвечают своим кодом. */
    private static final Map<String, String> PAGES = new ConcurrentHashMap<>();
    /** Потолок страниц {@code JobPosting} за чтение в этом тесте. */
    private static final int WINDOW = 3;
    private static final Map<String, Integer> STATUSES = new ConcurrentHashMap<>();
    private static final HttpServer SITE = startSite();

    @Autowired
    private SourceRepository sources;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Source source;

    /**
     * Сайт на 127.0.0.1 — внутренние адреса разрешены только в этом тесте.
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void siteProperties(DynamicPropertyRegistry registry) {
        registry.add("app.http.allow-private-addresses", () -> "true");
        registry.add("app.adapter.jobposting.max-pages", () -> String.valueOf(WINDOW));
    }

    /**
     * Остановка сайта.
     */
    @AfterAll
    static void stopSite() {
        SITE.stop(0);
    }

    /**
     * Чистые таблицы, пустой сайт и один источник — его кадровая страница.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"source_snapshot", "vacancy_revision", "crawl_run", "job_posting",
                "vacancy", "outbox_event", "task", "source"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        PAGES.clear();
        STATUSES.clear();
        source = sources.save(new Source(JobPostingAdapter.PROVIDER,
                "http://127.0.0.1:" + SITE.getAddress().getPort() + "/careers"));
    }

    /**
     * Сценарий 6. Вакансия, пропавшая из трёх полных чтений подряд, закрыта; запись вакансии,
     * публикации и история изменений сохранены; соседняя вакансия актуальна.
     */
    @Test
    void scenario6ConfirmedAbsenceClosesVacancyAndKeepsHistory() {
        publish("1", "Java Developer");
        publish("2", "QA Engineer");
        readOnce("r1");
        publish("1", "Senior Java Developer");
        readOnce("r2");
        withdraw("1");

        readOnce("r3");
        readOnce("r4");
        assertThat(vacancyState("Senior Java Developer")).isEqualTo("NEEDS_RECHECK");
        readOnce("r5");

        assertThat(vacancyState("Senior Java Developer")).isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM vacancy WHERE closed_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job_posting WHERE closed_at IS NOT NULL")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT old_value || '->' || new_value FROM vacancy_revision",
                String.class)).containsExactly("Java Developer->Senior Java Developer");
        assertThat(vacancyState("QA Engineer")).isEqualTo("ACTIVE");
    }

    /**
     * Сценарий 7, временный отказ. Три отказа кадровой страницы подряд: вакансия не закрыта, нуждается
     * в повторной проверке, сведения и дата подтверждения прежние, отсутствие не засчитано, источник —
     * «временные отказы»; после восстановления — та же вакансия актуальна, повторов нет, источник
     * доступен.
     */
    @Test
    void scenario7TemporaryFailureNeedsRecheckAndRecovers() {
        publish("1", "Java Developer");
        readOnce("r1");
        Object confirmedAt = lastConfirmedAt();
        STATUSES.put("/careers", 503);

        readOnce("r2");
        readOnce("r3");
        readOnce("r4");

        assertThat(vacancyState("Java Developer")).isEqualTo("NEEDS_RECHECK");
        assertThat(lastConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(count("SELECT max(missing_complete_reads) FROM job_posting")).isZero();
        assertThat(count("SELECT count(*) FROM crawl_run WHERE state = 'FAILED'")).isEqualTo(3);
        assertThat(availability()).isEqualTo("TEMP_FAILING");

        STATUSES.clear();
        readOnce("r5");

        assertThat(vacancyState("Java Developer")).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(1);
        assertThat(availability()).isEqualTo("OK");
    }

    /**
     * Сценарий 7, неполное чтение (граничный случай 1 технического документа §14). Страница одной
     * вакансии отвечает 503: изменения известной вакансии не применены, история и дата
     * подтверждения прежние, новая вакансия добавлена, известные нуждаются в повторной проверке;
     * после полного чтения изменения применены, повторов нет.
     */
    @Test
    void scenario7PartialReadKeepsKnownDataAndRecovers() {
        publish("1", "Java Developer");
        publish("2", "QA Engineer");
        readOnce("r1");
        Object confirmedAt = lastConfirmedAt();
        publish("1", "Senior Java Developer");
        publish("3", "DevOps Engineer");
        STATUSES.put("/jobs/2", 503);

        readOnce("r2");

        assertThat(jdbcTemplate.queryForObject("SELECT state FROM crawl_run ORDER BY id DESC LIMIT 1",
                String.class)).isEqualTo("PARTIAL");
        assertThat(vacancyState("Java Developer")).isEqualTo("NEEDS_RECHECK");
        assertThat(vacancyState("QA Engineer")).isEqualTo("NEEDS_RECHECK");
        assertThat(vacancyState("DevOps Engineer")).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM vacancy_revision")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_confirmed_at FROM vacancy WHERE title = 'Java Developer'", Object.class))
                .isEqualTo(confirmedAt);

        STATUSES.clear();
        readOnce("r3");

        assertThat(jdbcTemplate.queryForList("SELECT title || ':' || state FROM vacancy ORDER BY title",
                String.class)).containsExactly("DevOps Engineer:ACTIVE", "QA Engineer:ACTIVE",
                "Senior Java Developer:ACTIVE");
    }

    /**
     * Сценарий 9, сбор. Приложение остановилось во время чтения (задание захвачено, аренда истекла),
     * затем — после записи результата, но до отметки задания выполненным: оба раза задание
     * возвращается в очередь по аренде и выполняется снова; вакансии записаны по одной, повторов нет.
     */
    @Test
    void scenario9RestartDuringReadLosesNothingAndDuplicatesNothing() {
        publish("1", "Java Developer");
        taskService.enqueue(ReadSourceHandler.TYPE, "r1", ReadSourceHandler.payload(source.getId()));
        long taskId = jdbcTemplate.queryForObject("SELECT id FROM task WHERE task_key = 'r1'", Long.class);
        expireLease(taskId);

        assertThat(taskService.requeueDue()).isEqualTo(1);
        executor.execute(taskId);
        assertThat(taskState(taskId)).isEqualTo("DONE");
        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(1);

        expireLease(taskId);
        taskService.requeueDue();
        executor.execute(taskId);

        assertThat(taskState(taskId)).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForList("SELECT title || ':' || state FROM vacancy", String.class))
                .containsExactly("Java Developer:ACTIVE");
        assertThat(count("SELECT count(*) FROM job_posting")).isEqualTo(1);
    }

    /**
     * Окно по кругу (аудит §121), потолок {@value #WINDOW}. Три вакансии прочитаны полностью; добавлены ещё две —
     * ссылок 5: читаются 3 ссылки окна сегодняшних суток (как у адаптера), чтение полное. Известные вакансии окна
     * подтверждены, известные вне окна не изменились (состояние, дата подтверждения, счётчик отсутствия), новые
     * вне окна ещё не созданы. Временный отказ страницы окна — чтение неполное, известные вне окна
     * подтверждение не теряют. Затем ссылка вакансии 1 убрана с кадровой страницы: после трёх чтений она закрыта,
     * остальные — нет и отсутствие им не засчитано.
     */
    @Test
    void windowReadConfirmsReadPagesAndKeepsTheRest() {
        for (int id = 1; id <= WINDOW; id++) {
            publish(String.valueOf(id), "Job " + id);
        }
        readOnce("w1");
        assertThat(count("SELECT count(*) FROM vacancy WHERE state = 'ACTIVE'")).isEqualTo(WINDOW);
        jdbcTemplate.update("UPDATE vacancy SET last_confirmed_at = last_confirmed_at - interval '2 days'");
        publish("4", "Job 4");
        publish("5", "Job 5");
        List<String> window = todaysWindow(List.of("1", "2", "3", "4", "5"));

        readOnce("w2");

        assertThat(jdbcTemplate.queryForObject("SELECT state FROM crawl_run ORDER BY id DESC LIMIT 1",
                String.class)).isEqualTo("COMPLETE");
        for (String id : List.of("1", "2", "3", "4", "5")) {
            String title = "Job " + id;
            int created = count("SELECT count(*) FROM vacancy WHERE title = '" + title + "'");
            if (window.contains(id)) {
                assertThat(vacancyState(title)).isEqualTo("ACTIVE");
                assertThat(count("SELECT count(*) FROM vacancy WHERE title = '" + title
                        + "' AND last_confirmed_at > now() - interval '1 day'")).isEqualTo(1);
            } else if (Integer.parseInt(id) <= WINDOW) {
                assertThat(vacancyState(title)).isEqualTo("ACTIVE");
                assertThat(count("SELECT count(*) FROM vacancy WHERE title = '" + title
                        + "' AND last_confirmed_at < now() - interval '1 day'")).isEqualTo(1);
            } else {
                assertThat(created).isZero();
            }
        }
        assertThat(count("SELECT max(missing_complete_reads) FROM job_posting")).isZero();

        STATUSES.put("/jobs/" + window.get(0), 503);
        readOnce("w2-failed");
        STATUSES.clear();
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM crawl_run ORDER BY id DESC LIMIT 1",
                String.class)).isEqualTo("PARTIAL");
        for (int id = 1; id <= WINDOW; id++) {
            if (!window.contains(String.valueOf(id))) {
                assertThat(vacancyState("Job " + id)).isEqualTo("ACTIVE");
            }
        }

        withdraw("1");
        readOnce("w3");
        readOnce("w4");
        readOnce("w5");

        assertThat(vacancyState("Job 1")).isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM vacancy WHERE state = 'CLOSED'")).isEqualTo(1);
        assertThat(count("SELECT max(missing_complete_reads) FROM job_posting WHERE closed_at IS NULL")).isZero();
    }

    /**
     * Номера вакансий окна сегодняшних суток — та же формула, что у адаптера: {@value #WINDOW} подряд по кругу
     * с номера {@code (сутки UTC × потолок) mod число ссылок}; ссылки кадровой страницы идут по возрастанию пути.
     */
    private static List<String> todaysWindow(List<String> ids) {
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        int start = (int) Math.floorMod(day * WINDOW, (long) ids.size());
        List<String> window = new ArrayList<>();
        for (int index = 0; index < WINDOW; index++) {
            window.add(ids.get((start + index) % ids.size()));
        }
        return window;
    }

    private static HttpServer startSite() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String page = "/careers".equals(path) ? careersPage() : PAGES.get(path);
                byte[] bytes = page == null ? new byte[0] : page.getBytes(StandardCharsets.UTF_8);
                int status = STATUSES.getOrDefault(path, page == null ? 404 : 200);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
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

    /**
     * Кадровая страница — ссылки на все опубликованные вакансии.
     */
    private static String careersPage() {
        StringBuilder html = new StringBuilder("<html><body>");
        PAGES.keySet().stream().sorted().forEach(path -> html.append("<a href=\"").append(path).append("\">x</a>"));
        return html.append("</body></html>").toString();
    }

    private static void publish(String id, String title) {
        PAGES.put("/jobs/" + id, JOB_TEMPLATE.formatted(title));
    }

    private static void withdraw(String id) {
        PAGES.remove("/jobs/" + id);
    }

    private void readOnce(String taskKey) {
        taskService.enqueue(ReadSourceHandler.TYPE, taskKey, ReadSourceHandler.payload(source.getId()));
        executor.execute(jdbcTemplate.queryForObject("SELECT id FROM task WHERE task_key = ?", Long.class, taskKey));
    }

    private String vacancyState(String title) {
        return jdbcTemplate.queryForObject("SELECT state FROM vacancy WHERE title = ?", String.class, title);
    }

    /**
     * Как после остановки приложения: задание «выполняется», аренда истекла.
     */
    private void expireLease(long taskId) {
        jdbcTemplate.update("UPDATE task SET state = 'RUNNING', lease_until = now() - interval '1 second' WHERE id = ?",
                taskId);
    }

    private String taskState(long taskId) {
        return jdbcTemplate.queryForObject("SELECT state FROM task WHERE id = ?", String.class, taskId);
    }

    private String availability() {
        return jdbcTemplate.queryForObject("SELECT availability FROM source", String.class);
    }

    private Object lastConfirmedAt() {
        return jdbcTemplate.queryForObject("SELECT last_confirmed_at FROM vacancy ORDER BY id LIMIT 1", Object.class);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
