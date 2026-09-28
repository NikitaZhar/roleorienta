package com.roleorienta.worker.collect;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.adapter.greenhouse.GreenhouseAdapter;
import com.roleorienta.worker.adapter.greenhouse.GreenhouseStub;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskService;
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

/**
 * Чтение источника Greenhouse целиком: постановка чтения → задание → адаптер (заглушка) →
 * публикации и вакансии в PostgreSQL.
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
        jdbcTemplate.update("DELETE FROM job_posting");
        jdbcTemplate.update("DELETE FROM vacancy");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM source");
        source = sources.save(new Source(GreenhouseAdapter.PROVIDER, GreenhouseStub.BOARD));
    }

    /**
     * Первое чтение создаёт по вакансии на публикацию; повторная постановка в тот же день ничего не
     * добавляет.
     */
    @Test
    void firstReadCreatesVacancies() {
        STUB.respondWithJobs("101", "Java Developer", "102", "QA Engineer");

        planner.enqueueToday();
        planner.enqueueToday();
        executor.execute(onlyTaskId());

        assertThat(taskState()).isEqualTo("DONE");
        assertThat(count("job_posting")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT title FROM vacancy WHERE state = 'ACTIVE' ORDER BY title",
                String.class)).containsExactly("Java Developer", "QA Engineer");
    }

    /**
     * Повторное чтение обновляет сведения той же вакансии, не создавая новую.
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
    }

    /**
     * Временный отказ источника: задание ждёт повтора, сохранённые сведения не меняются.
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
