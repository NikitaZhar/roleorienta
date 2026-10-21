package com.roleorienta.worker.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Предрасчёт соответствий на PostgreSQL: позиции словаря в таблице, соответствия с версией и
 * объяснением, страны и формат работы («Remote - EU» — Словакия входит, удалённо), текст Greenhouse с закодированной разметкой, закрытая вакансия не сопоставляется,
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

    @Autowired
    private MatchRepository repository;

    private int run;

    /**
     * Чистые таблицы, три вакансии: Java в тексте, бухгалтер, закрытая.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"vacancy_language", "vacancy_position_match", "position", "source_snapshot",
                "vacancy_revision", "crawl_run", "job_posting", "vacancy", "company_source", "source", "outbox_event", "task"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO source (provider, board) VALUES ('greenhouse', 'acme')");
        vacancy("Software Engineer", "ACTIVE", "&lt;p&gt;Java, Java, Java&lt;/p&gt;", "Remote - EU");
        vacancy("Účtovník", "NEEDS_RECHECK", null, null);
        vacancy("Java Developer", "CLOSED", null, null);
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
                "SELECT count(*) FROM vacancy WHERE match_version LIKE ?", Integer.class, dictionary.version() + "/%"))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT 'SK' = ANY (work_countries) AND work_format = 'REMOTE'
                FROM vacancy WHERE title = 'Software Engineer'
                """, Boolean.class)).isTrue();

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

    /**
     * Правило портала (§149): вакансия государственного портала, места которой страны не дали, — в стране портала,
     * чужая страна в месте («Rakúsko» — Австрия по-словацки) остаётся.
     */
    @Test
    void portalVacancyWithoutCountryIsInCountryOfPortal() {
        jdbcTemplate.update("DELETE FROM job_posting");
        jdbcTemplate.update("DELETE FROM vacancy");
        long portal = jdbcTemplate.queryForObject("""
                INSERT INTO source (provider, board, country) VALUES (?, '12345678', 'SK') RETURNING id
                """, Long.class, StatePortalAdapter.PROVIDER);
        portalVacancy(portal, "Účtovníčka", "Miesto výkonu podľa dohody");
        portalVacancy(portal, "Účtovník", "Rakúsko");

        runMatch();

        assertThat(jdbcTemplate.queryForList("""
                SELECT title || ':' || array_to_string(work_countries, ',') FROM vacancy ORDER BY title
                """, String.class)).containsExactly("Účtovník:AT", "Účtovníčka:SK");
    }

    /**
     * Требования к языку записываются вместе с сопоставлением (§158): пункты списка — отдельные части текста.
     */
    @Test
    void languageRequirementsAreSaved() {
        vacancy("Data Analyst", "ACTIVE", "<ul><li>Fluent English</li><li>German is a plus</li></ul>", "Bratislava");

        runMatch();

        assertThat(jdbcTemplate.queryForList("""
                SELECT l.language || ':' || l.required FROM vacancy_language l JOIN vacancy v ON v.id = l.vacancy_id
                WHERE v.title = 'Data Analyst' ORDER BY 1
                """, String.class)).containsExactly("de:false", "en:true");
    }

    /**
     * Вакансия изменилась, пока сопоставление её читало (у неё новая отметка «сопоставить заново»),
     * — результат по старому тексту не записывается.
     */
    @Test
    void matchOfChangedVacancyIsNotSaved() {
        MatchRepository.VacancyText read = repository.vacanciesToMatch("any", 1).get(0);
        jdbcTemplate.update("UPDATE vacancy SET match_version = 'reset:changed' WHERE id = ?", read.id());

        assertThatThrownBy(() -> repository.saveMatches(read, "any", Map.of(),
                new LocationResolver.LocationFacts(Set.of(), null), List.of()))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private void vacancy(String title, String state, String content, String location) {
        Long vacancyId = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES (?, ?, 'https://example.com', now(), now()) RETURNING id
                """, Long.class, state, title);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, content, location,
                                         first_seen_at, last_confirmed_at)
                SELECT id, ?, ?, ?, 'https://example.com', ?, ?, now(), now() FROM source
                """, vacancyId, "ext-" + vacancyId, title, content, location);
    }

    private void portalVacancy(long sourceId, String title, String location) {
        Long vacancyId = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', ?, 'https://example.com', now(), now()) RETURNING id
                """, Long.class, title);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, location, first_seen_at,
                                         last_confirmed_at)
                VALUES (?, ?, ?, ?, 'https://example.com', ?, now(), now())
                """, sourceId, vacancyId, "ext-" + vacancyId, title, location);
    }

    private void runMatch() {
        String key = MatchVacanciesHandler.taskKey("test-" + run++);
        taskService.enqueue(MatchVacanciesHandler.TYPE, key, MatchVacanciesHandler.payload());
        executor.execute(jdbcTemplate.queryForObject("SELECT id FROM task WHERE task_key = ?", Long.class, key));
    }
}
