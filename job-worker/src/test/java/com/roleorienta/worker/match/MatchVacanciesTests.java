package com.roleorienta.worker.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Предрасчёт соответствий на PostgreSQL: позиции словаря в таблице, соответствия с версией и
 * объяснением, текст Greenhouse с закодированной разметкой, закрытая вакансия не сопоставляется,
 * повторный проход ничего не меняет, изменённая вакансия сопоставляется заново.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MatchVacanciesTests {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PositionDictionary dictionary;

    private int run;

    /**
     * Чистые таблицы, три вакансии: Java в тексте, бухгалтер, закрытая.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"vacancy_position_match", "position", "source_snapshot", "vacancy_revision",
                "crawl_run", "job_posting", "vacancy", "company_source", "source", "outbox_event", "task"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO source (provider, board) VALUES ('greenhouse', 'acme')");
        vacancy("Software Engineer", "ACTIVE", "&lt;p&gt;Java, Java, Java&lt;/p&gt;");
        vacancy("Účtovník", "NEEDS_RECHECK", null);
        vacancy("Java Developer", "CLOSED", null);
    }

    /**
     * Сопоставлены незакрытые вакансии; повтор ничего не делает; изменённая — заново.
     */
    @Test
    void matchesOpenVacanciesOncePerVersion() {
        runMatch();

        assertThat(jdbcTemplate.queryForList("""
                SELECT v.title || ':' || p.code || ':' || m.explanation FROM vacancy_position_match m
                JOIN vacancy v ON v.id = m.vacancy_id JOIN position p ON p.id = m.position_id ORDER BY 1
                """, String.class)).containsExactly("Software Engineer:java-developer:title: engineer; text: java x3",
                "Účtovník:accountant:title: uctovnik");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vacancy WHERE match_version = ?", Integer.class, dictionary.version()))
                .isEqualTo(2);

        runMatch();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vacancy_position_match", Integer.class))
                .isEqualTo(2);

        jdbcTemplate.update("UPDATE vacancy SET title = 'Hlavná účtovníčka', match_version = NULL "
                + "WHERE title = 'Účtovník'");
        runMatch();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT m.explanation FROM vacancy_position_match m JOIN vacancy v ON v.id = m.vacancy_id
                WHERE v.title = 'Hlavná účtovníčka'
                """, String.class)).isEqualTo("title: uctovnicka");
    }

    private void vacancy(String title, String state, String content) {
        Long vacancyId = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES (?, ?, 'https://example.com', now(), now()) RETURNING id
                """, Long.class, state, title);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, content, first_seen_at,
                                         last_confirmed_at)
                SELECT id, ?, ?, ?, 'https://example.com', ?, now(), now() FROM source
                """, vacancyId, "ext-" + vacancyId, title, content);
    }

    private void runMatch() {
        String key = MatchVacanciesHandler.taskKey("test-" + run++);
        taskService.enqueue(MatchVacanciesHandler.TYPE, key, MatchVacanciesHandler.payload());
        executor.execute(jdbcTemplate.queryForObject("SELECT id FROM task WHERE task_key = ?", Long.class, key));
    }
}
