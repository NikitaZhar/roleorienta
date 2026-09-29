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
 * </ul>
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
     * в повторной проверке, сведения и дата подтверждения прежние, отсутствие не засчитано; после
     * восстановления — та же вакансия актуальна, повторов нет.
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

        STATUSES.clear();
        readOnce("r5");

        assertThat(vacancyState("Java Developer")).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(1);
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

    private Object lastConfirmedAt() {
        return jdbcTemplate.queryForObject("SELECT last_confirmed_at FROM vacancy ORDER BY id LIMIT 1", Object.class);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
