package com.roleorienta.worker.collect;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.adapter.greenhouse.GreenhouseAdapter;
import com.roleorienta.worker.adapter.greenhouse.GreenhouseStub;
import com.roleorienta.worker.snapshot.SnapshotProperties;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Чтение источника Greenhouse целиком: постановка чтения → задание → адаптер (заглушка) →
 * публикации, вакансии, обходы и история вакансий в PostgreSQL, снимки ответов в S3-хранилище.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReadSourceFlowTests {

    private static final GreenhouseStub STUB = new GreenhouseStub();

    @Autowired
    private ReadSourcePlanner planner;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private SourceRepository sources;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private S3Client s3;

    @Autowired
    private SnapshotProperties snapshotProperties;

    private Source source;

    /**
     * Адаптер Greenhouse смотрит на заглушку; внутренние адреса разрешены только в этом тесте.
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void stubProperties(DynamicPropertyRegistry registry) {
        registry.add("app.adapter.greenhouse.base-url", STUB::baseUrl);
        registry.add("app.http.allow-private-addresses", () -> "true");
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    /**
     * Чистые таблицы и один источник Greenhouse.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM source_snapshot");
        jdbcTemplate.update("DELETE FROM vacancy_revision");
        jdbcTemplate.update("DELETE FROM crawl_run");
        jdbcTemplate.update("DELETE FROM job_posting");
        jdbcTemplate.update("DELETE FROM vacancy");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM source");
        source = sources.save(new Source(GreenhouseAdapter.PROVIDER, GreenhouseStub.BOARD));
    }

    /**
     * У источника без общего хоста (Workday) незавершённое чтение — оно ждёт повтора; часовой тик нового не ставит
     * (стенограмма §95, §108): чтения одного источника не копятся и не идут параллельно.
     */
    @Test
    void sourceWithPendingReadIsNotEnqueuedAgain() {
        Source workday = sources.save(new Source("workday", "pending.wd3.myworkdayjobs.com/en-US/careers"));
        jdbcTemplate.update("INSERT INTO task (type, task_key, payload, state, next_attempt_at, last_error) "
                + "VALUES (?, ?, ?::jsonb, 'WAITING', now() + interval '1 hour', 'timeout')",
                ReadSourceHandler.TYPE, "read:" + workday.getId() + ":2000-01-01T00",
                ReadSourceHandler.payload(workday.getId()));

        planner.enqueueDue();

        assertThat(count("task")).isEqualTo(1);
    }

    /**
     * Незавершённое чтение, не менявшееся больше суток (сообщение потеряно), новое не держит (аудит §98, §100).
     */
    @Test
    void staleReadDoesNotBlockNewRead() {
        jdbcTemplate.update("INSERT INTO task (type, task_key, payload, state, updated_at) "
                + "VALUES (?, ?, ?::jsonb, 'QUEUED', now() - interval '2 days')",
                ReadSourceHandler.TYPE, "read:" + source.getId() + ":2000-01-01",
                ReadSourceHandler.payload(source.getId()));

        planner.continueSharedHosts();

        assertThat(count("task")).isEqualTo(2);
    }

    /**
     * Первое чтение создаёт по вакансии на публикацию, полный обход и снимок ответа (объект в
     * S3-хранилище, ссылка с хешем в БД); повторная постановка, пока чтение в очереди, ничего не добавляет. Источник без компании
     * получает название работодателя от системы найма (аудит §65).
     */
    @Test
    void firstReadCreatesVacancies() {
        STUB.respondWithJobs("101", "Java Developer", "102", "QA Engineer");

        planner.continueSharedHosts();
        planner.continueSharedHosts();
        executor.execute(onlyTaskId());

        assertThat(taskState()).isEqualTo("DONE");
        assertThat(count("job_posting")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT title FROM vacancy WHERE state = 'ACTIVE' ORDER BY title",
                String.class)).containsExactly("Java Developer", "QA Engineer");
        assertThat(jdbcTemplate.queryForMap("SELECT state, postings_count, task_id FROM crawl_run"))
                .containsEntry("state", "COMPLETE")
                .containsEntry("postings_count", 2)
                .containsEntry("task_id", onlyTaskId());
        String key = jdbcTemplate.queryForObject("SELECT object_key FROM source_snapshot", String.class);
        assertThat(key).matches(source.getId() + "/[0-9a-f]{64}");
        assertThat(s3.getObjectAsBytes(request -> request.bucket(snapshotProperties.bucket()).key(key))
                .asUtf8String()).contains("Java Developer");
        assertThat(jdbcTemplate.queryForObject("SELECT employer_name FROM source", String.class))
                .isEqualTo("Acme Corp");
    }

    /**
     * Общий хост (Greenhouse): в очереди одно чтение за раз; следующий источник ставится, когда предыдущее чтение
     * завершилось или ждёт повтора; прочитанный меньше {@code read-after} назад не ставится (§103).
     */
    @Test
    void sharedHostReadsOneSourceAtATime() {
        Source second = sources.save(new Source(GreenhouseAdapter.PROVIDER, "second-board"));

        planner.continueSharedHosts();
        planner.continueSharedHosts();
        assertThat(count("task")).isEqualTo(1);

        jdbcTemplate.update("UPDATE task SET state = 'WAITING', next_attempt_at = now() + interval '1 hour', "
                + "last_error = 'timeout'");
        planner.continueSharedHosts();
        assertThat(jdbcTemplate.queryForList("SELECT task_key FROM task WHERE state = 'QUEUED'", String.class))
                .singleElement().asString().startsWith("read:" + second.getId() + ":");

        jdbcTemplate.update("UPDATE task SET state = 'DONE'");
        insertRun(source, "now() - interval '1 hour'");
        insertRun(second, "now() - interval '1 hour'");
        planner.continueSharedHosts();
        assertThat(count("task")).isEqualTo(2);
    }

    /**
     * Источник без общего хоста ставится раз в час, если его последнее чтение началось {@code read-after} (23 ч) назад
     * или раньше; прочитанный позже не ставится. Источники общего хоста этот тик не ставит (§103).
     */
    @Test
    void sourceIsReadWhenLastReadIsOlderThanReadAfter() {
        Source recent = sources.save(new Source("workday", "recent.wd3.myworkdayjobs.com/en-US/careers"));
        Source old = sources.save(new Source("workday", "old.wd3.myworkdayjobs.com/en-US/careers"));
        insertRun(recent, "now() - interval '22 hours'");
        insertRun(old, "now() - interval '23 hours 1 minute'");

        planner.enqueueDue();

        assertThat(jdbcTemplate.queryForList("SELECT task_key FROM task", String.class))
                .singleElement().asString().startsWith("read:" + old.getId() + ":");
    }

    private void insertRun(Source read, String startedAt) {
        jdbcTemplate.update("INSERT INTO crawl_run (source_id, started_at, finished_at, state, postings_count) "
                + "VALUES (?, " + startedAt + ", " + startedAt + ", 'COMPLETE', 0)", read.getId());
    }

    /**
     * Повторное чтение обновляет сведения той же вакансии, не создавая новую; смена позиции
     * записана в историю.
     */
    @Test
    void secondReadUpdatesExistingVacancy() {
        STUB.respondWithJobs("101", "Java Developer");
        readOnce("r1");
        STUB.respondWithJobs("101", "Senior Java Developer");

        readOnce("r2");

        assertThat(count("vacancy")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM vacancy", String.class))
                .isEqualTo("Senior Java Developer");
        assertThat(revisions()).containsExactly("TITLE:Java Developer->Senior Java Developer");
    }

    /**
     * Дата размещения (§127): нет у источника — пусто; появилась — записана; более поздняя — не заменяет раннюю.
     */
    @Test
    void readKeepsEarliestPostedDate() {
        STUB.respondWithJobs("101", "Java Developer");
        readOnce("r1");
        assertThat(postedAt()).isNull();
        STUB.respond(200, "{\"jobs\":[{\"id\":101,\"title\":\"Java Developer\",\"first_published\":"
                + "\"2026-09-20T10:00:00-04:00\",\"absolute_url\":\"https://job-boards.greenhouse.io/acme/jobs/101\"}]}");
        readOnce("r2");
        assertThat(postedAt()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
        STUB.respond(200, "{\"jobs\":[{\"id\":101,\"title\":\"Java Developer\",\"first_published\":"
                + "\"2026-10-01\",\"absolute_url\":\"https://job-boards.greenhouse.io/acme/jobs/101\"}]}");
        readOnce("r3");
        assertThat(postedAt()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
    }

    private Instant postedAt() {
        Timestamp posted = jdbcTemplate.queryForObject("SELECT posted_at FROM vacancy", Timestamp.class);
        return posted == null ? null : posted.toInstant();
    }

    /**
     * Временный отказ источника: задание ждёт повтора, сохранённые сведения не меняются, обход
     * записан как неудачный с видом отказа.
     */
    @Test
    void unavailableSourceSchedulesRetryAndKeepsData() {
        STUB.respondWithJobs("101", "Java Developer");
        readOnce("r1");
        STUB.respond(503, "");

        readOnce("r2");

        assertThat(jdbcTemplate.queryForObject("SELECT state FROM task WHERE task_key = 'r2'", String.class))
                .isEqualTo("WAITING");
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM vacancy", String.class))
                .isEqualTo("Java Developer");
        assertThat(jdbcTemplate.queryForList(
                "SELECT state || ':' || coalesce(failure_kind, '-') FROM crawl_run ORDER BY id", String.class))
                .containsExactly("COMPLETE:-", "FAILED:TEMPORARY");
    }

    /**
     * Публикации нет в полном чтении — повторная проверка; нет в трёх полных чтениях подряд —
     * вакансия закрыта.
     */
    @Test
    void missingPostingClosesAfterThreeCompleteReads() {
        STUB.respondWithJobs("101", "Java Developer", "102", "QA Engineer");
        readOnce("r1");
        STUB.respondWithJobs("102", "QA Engineer");

        readOnce("r2");
        assertThat(vacancyState("Java Developer")).isEqualTo("NEEDS_RECHECK");

        readOnce("r3");
        readOnce("r4");
        assertThat(vacancyState("Java Developer")).isEqualTo("CLOSED");
        assertThat(vacancyState("QA Engineer")).isEqualTo("ACTIVE");
    }

    /**
     * Закрытая публикация появилась снова — та же вакансия снова актуальна, в истории «опубликована
     * снова»; неизменные сведения ревизий не дают.
     */
    @Test
    void reappearedPostingReopensSameVacancy() {
        STUB.respondWithJobs("101", "Java Developer");
        readOnce("r1");
        STUB.respondWithJobs();
        readOnce("r2");
        readOnce("r3");
        readOnce("r4");
        STUB.respondWithJobs("101", "Java Developer");

        readOnce("r5");

        assertThat(count("vacancy")).isEqualTo(1);
        assertThat(vacancyState("Java Developer")).isEqualTo("ACTIVE");
        assertThat(revisions()).containsExactly("REOPENED:null->null");
    }

    /**
     * Ссылка публикации не http(s) (аудит §66): новая публикация не записывается, известная не закрывается и не
     * меняет ссылку — вакансия нуждается в повторной проверке.
     */
    @Test
    void postingWithoutWebLinkIsNotRecorded() {
        STUB.respondWithJobs("101", "Java Developer");
        readOnce("r1");
        String badLinks = "{\"jobs\":[{\"id\":101,\"title\":\"Java Developer\","
                + "\"absolute_url\":\"javascript:alert(1)\"},"
                + "{\"id\":102,\"title\":\"QA Engineer\",\"absolute_url\":\"\"}]}";
        STUB.respond(200, badLinks);

        readOnce("r2");
        readOnce("r3");
        readOnce("r4");

        assertThat(count("vacancy")).isEqualTo(1);
        assertThat(vacancyState("Java Developer")).isEqualTo("NEEDS_RECHECK");
        assertThat(jdbcTemplate.queryForObject("SELECT primary_url FROM vacancy", String.class))
                .isEqualTo("https://job-boards.greenhouse.io/acme/jobs/101");
    }

    /**
     * Отказ источника: вакансия нуждается в повторной проверке и не закрывается.
     */
    @Test
    void unavailableSourceNeedsRecheck() {
        STUB.respondWithJobs("101", "Java Developer");
        readOnce("r1");
        STUB.respond(503, "");

        readOnce("r2");
        readOnce("r3");
        readOnce("r4");

        assertThat(vacancyState("Java Developer")).isEqualTo("NEEDS_RECHECK");
    }

    private List<String> revisions() {
        return jdbcTemplate.queryForList("SELECT field || ':' || coalesce(old_value, 'null') || '->' "
                + "|| coalesce(new_value, 'null') FROM vacancy_revision ORDER BY id", String.class);
    }

    private String vacancyState(String title) {
        return jdbcTemplate.queryForObject("SELECT state FROM vacancy WHERE title = ?", String.class, title);
    }

    private void readOnce(String taskKey) {
        taskService.enqueue(ReadSourceHandler.TYPE, taskKey, ReadSourceHandler.payload(source.getId()));
        executor.execute(jdbcTemplate.queryForObject(
                "SELECT id FROM task WHERE task_key = ?", Long.class, taskKey));
    }

    private long onlyTaskId() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM task", Long.class);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private String taskState() {
        return jdbcTemplate.queryForObject("SELECT state FROM task", String.class);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
