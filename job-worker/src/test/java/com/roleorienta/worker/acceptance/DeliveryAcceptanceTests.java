package com.roleorienta.worker.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.delivery.PassPlanner;
import com.roleorienta.worker.delivery.RunPassHandler;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Приёмочные сценарии выдачи (бизнес-описание §4.5, §7.4-А; технический документ §7, §14) на
 * PostgreSQL: вакансии, соответствия позиции, страна и формат заданы данными; проходы планируются
 * по окнам вручную, задания {@code RUN_PASS} выполняются из теста.
 *
 * <ul>
 *   <li>1 — лимит порции: 45 вакансий, лимит 20 — порции 20, 20, 5; граница порции внутри вакансий
 *       одной компании; без пропусков и повторов.</li>
 *   <li>2 — новые вакансии между проходами попадают в следующую порцию.</li>
 *   <li>8 — срок без подтверждения: не подтверждённая дольше срока вакансия не выдаётся; после
 *       подтверждения выдаётся; уже выданная после возвращения не выдаётся как новая.</li>
 *   <li>9 (выдача) — повтор задания прохода и повторное планирование окна порцию не удваивают.</li>
 *   <li>Отбор в порции: исключает только явное несоответствие страны или формата, закрытие и
 *       отметку «не подходит» (бизнес-описание §4.4).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DeliveryAcceptanceTests {

    private static final Instant FIRST_WINDOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String[] TABLES = {"delivered_vacancy", "pass_run", "unsuitable_mark", "search_condition",
        "user_account", "vacancy_position_match", "position", "source_snapshot", "vacancy_revision", "crawl_run",
        "job_posting", "vacancy", "company_source", "source", "outbox_event", "task"};

    @Autowired
    private PassPlanner planner;

    @Autowired
    private RunPassHandler handler;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long positionId;
    private long userId;
    private long conditionId;
    private int passes;

    /**
     * Чистые таблицы; позиция, две компании (источники), пользователь с условиями: Словакия, Java,
     * формат не задан, лимит 20.
     */
    @BeforeEach
    void setUp() {
        cleanUp();
        positionId = jdbcTemplate.queryForObject(
                "INSERT INTO position (code, name) VALUES ('java-developer', 'Java developer') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO source (provider, board) VALUES ('greenhouse', 'acme'), ('greenhouse', 'beta')");
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO user_account (email, password_hash) VALUES ('user@example.com', '-') RETURNING id",
                Long.class);
        conditionId = jdbcTemplate.queryForObject("""
                INSERT INTO search_condition (user_id, countries, position_id, portion_limit, active)
                VALUES (?, '{SK}', ?, 20, TRUE) RETURNING id
                """, Long.class, userId, positionId);
    }

    /**
     * Строки выдачи ссылаются на вакансии и позиции — остальные тесты удаляют их без этих таблиц.
     */
    @AfterEach
    void cleanUp() {
        for (String table : TABLES) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    /**
     * Сценарий 1: 30 вакансий компании acme, затем 15 — beta; порции 20, 20, 5, четвёртая пуста.
     */
    @Test
    void scenario1PortionLimit() {
        for (int order = 1; order <= 45; order++) {
            vacancy("Java Developer " + order, order, order <= 30 ? "acme" : "beta");
        }

        List<Integer> portions = List.of(runPass(), runPass(), runPass(), runPass());

        assertThat(portions).containsExactly(20, 20, 5, 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT vacancy_id) FROM delivered_vacancy WHERE search_condition_id = ?",
                Integer.class, conditionId)).isEqualTo(45);
        assertThat(jdbcTemplate.queryForList("""
                SELECT v.title FROM delivered_vacancy d JOIN vacancy v ON v.id = d.vacancy_id
                JOIN pass_run r ON r.id = d.pass_run_id ORDER BY r.window_start, v.first_seen_at LIMIT 2 OFFSET 19
                """, String.class)).containsExactly("Java Developer 20", "Java Developer 21");
        assertThat(company("Java Developer 20")).isEqualTo(company("Java Developer 21")).isEqualTo("acme");
    }

    /**
     * Сценарий 2: пять вакансий — первая порция; три новые — следующая.
     */
    @Test
    void scenario2NewVacanciesGoToNextPortion() {
        for (int order = 1; order <= 5; order++) {
            vacancy("Java Developer " + order, order, "acme");
        }
        int first = runPass();
        for (int order = 6; order <= 8; order++) {
            vacancy("Java Developer " + order, order, "beta");
        }

        assertThat(List.of(first, runPass(), runPass())).containsExactly(5, 3, 0);
    }

    /**
     * Сценарий 8: не подтверждённая 31 день вакансия не выдаётся; подтверждённая снова — выдаётся;
     * выданная, скрытая и вернувшаяся — не выдаётся повторно.
     */
    @Test
    void scenario8HiddenAfterTermNotDeliveredAsNewOnReturn() {
        long fresh = vacancy("Java Developer 1", 1, "acme");
        long stale = vacancy("Java Developer 2", 2, "acme");
        confirmedDaysAgo(stale, 31);

        assertThat(runPass()).isEqualTo(1);

        confirmedDaysAgo(stale, 0);
        assertThat(runPass()).isEqualTo(1);

        confirmedDaysAgo(fresh, 31);
        assertThat(runPass()).isZero();
        confirmedDaysAgo(fresh, 0);
        assertThat(runPass()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM delivered_vacancy", Integer.class)).isEqualTo(2);
    }

    /**
     * Сценарий 9 (выдача): повтор задания того же прохода ничего не добавляет; повторное планирование
     * окна — тот же проход без новых заданий; неактивная версия условий порций не получает.
     */
    @Test
    void scenario9RepeatedPassDoesNotDoubleThePortion() {
        for (int order = 1; order <= 25; order++) {
            vacancy("Java Developer " + order, order, "acme");
        }
        long passRunId = planner.plan(FIRST_WINDOW);
        TaskRecord task = new TaskRecord(0, RunPassHandler.TYPE, RunPassHandler.payload(passRunId, conditionId), 0);

        handler.handle(task);
        handler.handle(task);

        assertThat(delivered()).isEqualTo(20);
        assertThat(planner.plan(FIRST_WINDOW)).isEqualTo(passRunId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE type = ?", Integer.class,
                RunPassHandler.TYPE)).isEqualTo(1);

        jdbcTemplate.update("UPDATE search_condition SET active = FALSE");
        handler.handle(new TaskRecord(0, RunPassHandler.TYPE,
                RunPassHandler.payload(planner.plan(FIRST_WINDOW.plus(1, ChronoUnit.HOURS)), conditionId), 0));
        assertThat(delivered()).isEqualTo(20);
    }

    /**
     * Отбор в порции (условия: Словакия, удалённо): явные несоответствия страны и формата, закрытая
     * и отмеченная «не подходит» вакансии и вакансия без позиции не выдаются; неясные — выдаются.
     */
    @Test
    void portionExcludesOnlyExplicitMismatches() {
        jdbcTemplate.update("UPDATE search_condition SET work_format = 'REMOTE'");
        facts(vacancy("Slovakia remote", 1, "acme"), "{SK}", false, "REMOTE");
        facts(vacancy("Czechia", 2, "acme"), "{CZ}", false, "REMOTE");
        facts(vacancy("Czechia and unclear place", 3, "acme"), "{CZ}", true, "REMOTE");
        facts(vacancy("Worldwide", 4, "acme"), "{*}", false, "REMOTE");
        facts(vacancy("No country", 5, "acme"), "{}", false, null);
        facts(vacancy("Office", 6, "acme"), "{SK}", false, "OFFICE");
        facts(vacancy("Conflicting format", 7, "acme"), "{SK}", false, "CONFLICT");
        jdbcTemplate.update("UPDATE vacancy SET state = 'CLOSED' WHERE id = ?", vacancy("Closed", 8, "acme"));
        jdbcTemplate.update("UPDATE vacancy SET state = 'NEEDS_RECHECK' WHERE id = ?", vacancy("Recheck", 9, "acme"));
        jdbcTemplate.update("INSERT INTO unsuitable_mark (user_id, vacancy_id) VALUES (?, ?)", userId,
                vacancy("Marked", 10, "acme"));
        jdbcTemplate.update("DELETE FROM vacancy_position_match WHERE vacancy_id = ?", vacancy("No position", 11, "acme"));

        runPass();

        assertThat(jdbcTemplate.queryForList("""
                SELECT v.title FROM delivered_vacancy d JOIN vacancy v ON v.id = d.vacancy_id ORDER BY v.first_seen_at
                """, String.class)).containsExactly("Slovakia remote", "Czechia and unclear place", "Worldwide",
                "No country", "Conflicting format", "Recheck");
    }

    /**
     * Проход следующего окна: планирование и выполнение его заданий.
     *
     * @return размер порции
     */
    private int runPass() {
        long passRunId = planner.plan(FIRST_WINDOW.plus(passes++, ChronoUnit.HOURS));
        jdbcTemplate.queryForList("SELECT id FROM task WHERE type = ? AND state = 'QUEUED' ORDER BY id", Long.class,
                RunPassHandler.TYPE).forEach(executor::execute);
        return jdbcTemplate.queryForObject("SELECT count(*) FROM delivered_vacancy WHERE pass_run_id = ?",
                Integer.class, passRunId);
    }

    /**
     * Актуальная вакансия компании: подтверждена сейчас, Словакия, формат не указан, совпадает с
     * позицией; порядок потока — {@code order}.
     */
    private long vacancy(String title, int order, String company) {
        long vacancyId = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, work_countries,
                                     country_uncertain)
                VALUES ('ACTIVE', ?, 'https://example.com', now() - (1000 - ?) * interval '1 second', now(), '{SK}',
                        FALSE) RETURNING id
                """, Long.class, title, order);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at,
                                         last_confirmed_at)
                SELECT id, ?, ?, ?, 'https://example.com', now(), now() FROM source WHERE board = ?
                """, vacancyId, "ext-" + vacancyId, title, company);
        jdbcTemplate.update("""
                INSERT INTO vacancy_position_match (vacancy_id, position_id, dictionary_version, explanation)
                VALUES (?, ?, 'test', 'test')
                """, vacancyId, positionId);
        return vacancyId;
    }

    private void facts(long vacancyId, String countries, boolean uncertain, String format) {
        jdbcTemplate.update("""
                UPDATE vacancy SET work_countries = ?::text[], country_uncertain = ?, work_format = ? WHERE id = ?
                """, countries, uncertain, format, vacancyId);
    }

    private void confirmedDaysAgo(long vacancyId, int days) {
        jdbcTemplate.update("UPDATE vacancy SET last_confirmed_at = now() - ? * interval '1 day' WHERE id = ?", days,
                vacancyId);
    }

    private String company(String title) {
        return jdbcTemplate.queryForObject("""
                SELECT s.board FROM job_posting p JOIN source s ON s.id = p.source_id WHERE p.title = ?
                """, String.class, title);
    }

    private int delivered() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM delivered_vacancy", Integer.class);
    }
}
