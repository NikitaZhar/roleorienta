package com.roleorienta.worker.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.delivery.PassPlanner;
import com.roleorienta.worker.delivery.RunPassHandler;
import com.roleorienta.worker.match.Fit;
import com.roleorienta.worker.match.WorkFormat;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *   <li>1 — лимита порции нет (§120): все 45 подходящих вакансий — первым проходом, следующий пуст.</li>
 *   <li>2 — новые вакансии между проходами попадают в следующую порцию.</li>
 *   <li>8 — срок без подтверждения: не подтверждённая дольше срока вакансия не выдаётся; после
 *       подтверждения выдаётся; уже выданная после возвращения не выдаётся как новая.</li>
 *   <li>9 (выдача) — повтор задания прохода и повторное планирование окна порцию не удваивают.</li>
 *   <li>Отбор в порции: страна условий среди определённых стран вакансии или страна не определена; только «*» — нет
 *       (§148); исключает явное несоответствие формата, закрытие, «Не интересно» и «Мои отклики» (бизнес-описание
 *       §4.4, §6);
 *       совпадает с оценкой {@code Fit} на полном наборе сочетаний (аудит §154).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DeliveryAcceptanceTests {

    private static final Instant FIRST_WINDOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String[] TABLES = {"delivered_vacancy", "pass_run", "vacancy_status_history",
        "vacancy_user_status", "search_condition", "digest_vacancy",
        "user_account", "vacancy_language", "vacancy_position_match",
        "position_term", "position", "source_snapshot", "vacancy_revision", "crawl_run", "job_posting", "vacancy",
        "company_source", "source", "outbox_event", "task"};

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
     * Чистые таблицы; позиция, две компании (источники), пользователь с условиями: Словакия, Java (позиция —
     * {@code search_condition_position}, §126),
     * формат не задан (лимит порции 20 записан — столбец обязателен, выдача его не читает, §120).
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
                INSERT INTO search_condition (user_id, countries, portion_limit, active)
                VALUES (?, '{SK}', 20, TRUE) RETURNING id
                """, Long.class, userId);
        jdbcTemplate.update("INSERT INTO search_condition_position (search_condition_id, position_id) VALUES (?, ?)",
                conditionId, positionId);
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
     * Сценарий 1 (решение владельца 2026-10-15, §120): лимита порции нет — 30 вакансий компании acme и 15 — beta
     * выдаются первым же проходом все 45 (лимит условий 20 не действует), следующий проход пуст.
     */
    @Test
    void scenario1AllMatchingAtOnce() {
        for (int order = 1; order <= 45; order++) {
            vacancy("Java Developer " + order, order, order <= 30 ? "acme" : "beta");
        }

        List<Integer> portions = List.of(runPass(), runPass());

        assertThat(portions).containsExactly(45, 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT vacancy_id) FROM delivered_vacancy WHERE search_condition_id = ?",
                Integer.class, conditionId)).isEqualTo(45);
    }

    /**
     * Старые вакансии (§217): размещённая работодателем больше 4 месяцев назад не выдаётся; моложе и без даты
     * размещения — выдаются.
     */
    @Test
    void staleVacancyIsNotDelivered() {
        long stale = vacancy("Java Developer stale", 1, "acme");
        long recent = vacancy("Java Developer recent", 2, "acme");
        vacancy("Java Developer undated", 3, "acme");
        postedDaysAgo(stale, 130);
        postedDaysAgo(recent, 100);

        assertThat(runPass()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM delivered_vacancy WHERE vacancy_id = ?",
                Integer.class, stale)).isZero();
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

        assertThat(delivered()).isEqualTo(25);
        assertThat(planner.plan(FIRST_WINDOW)).isEqualTo(passRunId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE type = ?", Integer.class,
                RunPassHandler.TYPE)).isEqualTo(1);

        jdbcTemplate.update("UPDATE search_condition SET active = FALSE");
        handler.handle(new TaskRecord(0, RunPassHandler.TYPE,
                RunPassHandler.payload(planner.plan(FIRST_WINDOW.plus(1, ChronoUnit.HOURS)), conditionId), 0));
        assertThat(delivered()).isEqualTo(25);
    }

    /**
     * Отбор в порции (условия: Словакия, удалённо): выдаются вакансии, среди определённых стран которых есть
     * Словакия, и вакансии без определённой страны (с пометкой); другая страна и только «*» — удалённо «весь мир» —
     * нет (решения владельца 2026-10-20, §148);
     * явное несоответствие формата (офис при «удалённо»), закрытая вакансия, вакансии «Не интересно» и «Резюме
     * отправлено» (§156) и вакансия без позиции не выдаются; противоречивый формат, гибрид (подходит к любому формату,
     * §125) и просмотренная вакансия — выдаются.
     */
    @Test
    void portionKeepsOnlyCountryOfConditions() {
        jdbcTemplate.update("UPDATE search_condition SET work_format = 'REMOTE'");
        facts(vacancy("Slovakia remote", 1, "acme"), "{SK}", "REMOTE");
        facts(vacancy("Czechia", 2, "acme"), "{CZ}", "REMOTE");
        facts(vacancy("Worldwide", 4, "acme"), "{*}", "REMOTE");
        facts(vacancy("No country", 5, "acme"), "{}", null);
        facts(vacancy("Office", 6, "acme"), "{SK}", "OFFICE");
        facts(vacancy("Hybrid", 12, "acme"), "{SK}", "HYBRID");
        facts(vacancy("Conflicting format", 7, "acme"), "{SK}", "CONFLICT");
        jdbcTemplate.update("UPDATE vacancy SET state = 'CLOSED' WHERE id = ?", vacancy("Closed", 8, "acme"));
        jdbcTemplate.update("UPDATE vacancy SET state = 'NEEDS_RECHECK' WHERE id = ?", vacancy("Recheck", 9, "acme"));
        status(vacancy("Not interested", 10, "acme"), "NOT_INTERESTED");
        status(vacancy("Applied", 13, "acme"), "APPLIED");
        status(vacancy("Viewed", 14, "acme"), "VIEWED");
        jdbcTemplate.update("DELETE FROM vacancy_position_match WHERE vacancy_id = ?", vacancy("No position", 11, "acme"));

        runPass();

        assertThat(jdbcTemplate.queryForList("""
                SELECT v.title FROM delivered_vacancy d JOIN vacancy v ON v.id = d.vacancy_id ORDER BY v.first_seen_at
                """, String.class)).containsExactly("Slovakia remote", "No country", "Conflicting format", "Recheck", "Hybrid", "Viewed");
    }

    /**
     * Правило отбора по стране и формату записано и в SQL выдачи ({@code DeliveryRepository.PORTION}), и в
     * {@code Fit} (аудит §154: копии держались только на JavaDoc). Здесь они сверяются на всех сочетаниях: страны
     * вакансии × формат вакансии × формат условий; выдаётся ровно то, у чего {@code Fit} не даёт несоответствия.
     */
    @Test
    void portionAgreesWithFitOnEveryCombination() {
        List<Set<String>> countrySets = List.of(Set.of("SK"), Set.of("CZ"), Set.of(), Set.of("*"), Set.of("SK", "*"),
                Set.of("CZ", "*"));
        List<WorkFormat> vacancyFormats = Arrays.asList(null, WorkFormat.OFFICE, WorkFormat.HYBRID, WorkFormat.REMOTE,
                WorkFormat.CONFLICT);
        List<Case> cases = new ArrayList<>();
        for (Set<String> countries : countrySets) {
            for (WorkFormat format : vacancyFormats) {
                String array = "{" + String.join(",", countries.stream().sorted().toList()) + "}";
                Case example = new Case(array + "/" + format, countries, format);
                facts(vacancy(example.title(), cases.size() + 1, "acme"), array,
                        format == null ? null : format.name());
                cases.add(example);
            }
        }

        for (WorkFormat conditionFormat : Arrays.asList(null, WorkFormat.OFFICE, WorkFormat.REMOTE)) {
            jdbcTemplate.update("DELETE FROM delivered_vacancy");
            jdbcTemplate.update("UPDATE search_condition SET work_format = ? WHERE id = ?",
                    conditionFormat == null ? null : conditionFormat.name(), conditionId);
            runPass();

            Set<String> delivered = new HashSet<>(jdbcTemplate.queryForList("""
                    SELECT v.title FROM delivered_vacancy d JOIN vacancy v ON v.id = d.vacancy_id
                    WHERE d.search_condition_id = ?
                    """, String.class, conditionId));
            Set<String> expected = new HashSet<>(cases.stream()
                    .filter(example -> Fit.country(example.countries(), Set.of("SK")) != Fit.MISMATCH
                            && Fit.format(example.format(), conditionFormat) != Fit.MISMATCH)
                    .map(Case::title).toList());
            assertThat(delivered).as("condition format %s", conditionFormat).isEqualTo(expected);
        }
    }

    /**
     * Условия «офис» (решения владельца 2026-10-16, §124, §125): выдаются офисная, гибридная и вакансия без формата;
     * удалённая — нет.
     */
    @Test
    void officeConditionIncludesHybrid() {
        jdbcTemplate.update("UPDATE search_condition SET work_format = 'OFFICE'");
        facts(vacancy("Office", 1, "acme"), "{SK}", "OFFICE");
        facts(vacancy("Hybrid", 2, "acme"), "{SK}", "HYBRID");
        facts(vacancy("Remote", 3, "acme"), "{SK}", "REMOTE");
        facts(vacancy("No format", 4, "acme"), "{SK}", null);

        runPass();

        assertThat(jdbcTemplate.queryForList("""
                SELECT v.title FROM delivered_vacancy d JOIN vacancy v ON v.id = d.vacancy_id ORDER BY v.first_seen_at
                """, String.class)).containsExactly("Office", "Hybrid", "No format");
    }

    /**
     * Несколько позиций в условиях (§126): выдаются вакансии каждой из них, вакансия двух позиций — один раз;
     * вакансия третьей позиции, которой нет в условиях, — нет.
     */
    @Test
    void portionCoversEveryPositionOfConditions() {
        long devops = jdbcTemplate.queryForObject(
                "INSERT INTO position (code, name) VALUES ('devops-engineer', 'DevOps') RETURNING id", Long.class);
        long qa = jdbcTemplate.queryForObject(
                "INSERT INTO position (code, name) VALUES ('qa-engineer', 'QA') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO search_condition_position (search_condition_id, position_id) VALUES (?, ?)",
                conditionId, devops);
        vacancy("Java", 1, "acme");
        rematch(vacancy("DevOps", 2, "acme"), devops);
        long both = vacancy("Java and DevOps", 3, "acme");
        jdbcTemplate.update("""
                INSERT INTO vacancy_position_match (vacancy_id, position_id, dictionary_version, explanation)
                VALUES (?, ?, 'test', 'test')
                """, both, devops);
        rematch(vacancy("QA", 4, "acme"), qa);

        assertThat(runPass()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT v.title FROM delivered_vacancy d JOIN vacancy v ON v.id = d.vacancy_id ORDER BY v.first_seen_at
                """, String.class)).containsExactly("Java", "DevOps", "Java and DevOps");
    }

    /**
     * Группы ISCO-08 в условиях (§204): к группе подходит вакансия, сопоставленная позиции словаря этой группы (позиции
     * нет в условиях), вакансия с кодом группы у публикации (государственный портал, без позиции) и с группой,
     * распознанной по названию (§215); вакансия с кодом другой группы — нет.
     */
    @Test
    void portionCoversGroupsOfConditions() {
        long devops = jdbcTemplate.queryForObject("""
                INSERT INTO position (code, name, occupation_code) VALUES ('devops-engineer', 'DevOps', '2512')
                RETURNING id""", Long.class);
        jdbcTemplate.update("INSERT INTO search_condition_occupation (search_condition_id, code) VALUES (?, '2512'), "
                + "(?, '8332')", conditionId, conditionId);
        vacancy("Java", 1, "acme");
        rematch(vacancy("DevOps", 2, "acme"), devops);
        coded(vacancy("Truck", 3, "acme"), "8332");
        coded(vacancy("Cook", 4, "acme"), "5120");
        long recognized = vacancy("Recognized driver", 5, "acme");
        coded(recognized, null);
        jdbcTemplate.update("UPDATE job_posting SET title_occupation_code = '8332' WHERE vacancy_id = ?", recognized);

        assertThat(runPass()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForList("""
                SELECT v.title FROM delivered_vacancy d JOIN vacancy v ON v.id = d.vacancy_id ORDER BY v.first_seen_at
                """, String.class)).containsExactly("Java", "DevOps", "Truck", "Recognized driver");
    }

    /** Вакансия без позиции словаря, у публикации — код группы ISCO-08. */
    private void coded(long vacancyId, String group) {
        jdbcTemplate.update("DELETE FROM vacancy_position_match WHERE vacancy_id = ?", vacancyId);
        jdbcTemplate.update("UPDATE job_posting SET occupation_code = ? WHERE vacancy_id = ?", group, vacancyId);
    }

    /** Соответствие вакансии — только другой позиции. */
    private void rematch(long vacancyId, long otherPositionId) {
        jdbcTemplate.update("UPDATE vacancy_position_match SET position_id = ? WHERE vacancy_id = ?", otherPositionId,
                vacancyId);
    }

    /**
     * Сочетание для сверки выдачи с {@code Fit}.
     *
     * @param title     название вакансии (метка сочетания)
     * @param countries страны вакансии
     * @param format    формат вакансии; {@code null} — не указан
     */
    private record Case(String title, Set<String> countries, WorkFormat format) {
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
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, work_countries)
                VALUES ('ACTIVE', ?, 'https://example.com', now() - (1000 - ?) * interval '1 second', now(), '{SK}')
                RETURNING id
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

    /** Текущий статус пользователя у вакансии (§156). */
    private void status(long vacancyId, String status) {
        jdbcTemplate.update("INSERT INTO vacancy_user_status (user_id, vacancy_id, status, changed_at) "
                + "VALUES (?, ?, ?, now())", userId, vacancyId, status);
    }

    private void facts(long vacancyId, String countries, String format) {
        jdbcTemplate.update("""
                UPDATE vacancy SET work_countries = ?::text[], work_format = ? WHERE id = ?
                """, countries, format, vacancyId);
    }

    private void confirmedDaysAgo(long vacancyId, int days) {
        jdbcTemplate.update("UPDATE vacancy SET last_confirmed_at = now() - ? * interval '1 day' WHERE id = ?", days,
                vacancyId);
    }

    private void postedDaysAgo(long vacancyId, int days) {
        jdbcTemplate.update("UPDATE vacancy SET posted_at = now() - ? * interval '1 day' WHERE id = ?", days,
                vacancyId);
    }

    private int delivered() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM delivered_vacancy", Integer.class);
    }
}
