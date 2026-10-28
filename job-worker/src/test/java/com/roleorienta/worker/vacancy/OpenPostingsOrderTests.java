package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Порядок незакрытых публикаций для проверки пропавших (§218): дольше не подтверждённые — первыми, закрытых нет. Иначе
 * проверка, ограниченная числом за чтение, каждый раз доставалась бы одним и тем же публикациям.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OpenPostingsOrderTests {

    @Autowired
    private PostingRecorder recorder;

    @Autowired
    private SourceRepository sources;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Чистые публикации и вакансии (сначала ссылки на вакансии, которые оставляют другие тесты). */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "vacancy_status_history", "vacancy_user_status",
                "vacancy_language", "vacancy_position_match", "company_source", "source_snapshot", "vacancy_revision",
                "crawl_run", "job_posting", "vacancy", "outbox_event", "task", "source"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    /** Подтверждённые давно — первыми, вчерашняя — после них, закрытая — не возвращается. */
    @Test
    void longestUnconfirmedComeFirst() {
        Source source = sources.save(new Source("workday", "qworder.wd3.myworkdayjobs.com/careers"));
        read(source, "fresh", "old", "older", "closed");
        confirmedDaysAgo(source, "fresh", 1);
        confirmedDaysAgo(source, "old", 10);
        confirmedDaysAgo(source, "older", 20);
        jdbcTemplate.update("UPDATE job_posting SET closed_at = now() WHERE source_id = ? AND external_id = 'closed'",
                source.getId());

        assertThat(recorder.openExternalIds(source)).containsExactly("older", "old", "fresh");
    }

    private void read(Source source, String... externalIds) {
        long taskId = jdbcTemplate.queryForObject("INSERT INTO task (type, task_key, payload, state) "
                + "VALUES ('READ_SOURCE', gen_random_uuid()::text, '{}', 'QUEUED') RETURNING id", Long.class);
        CrawlRun run = recorder.startRun(source, taskId);
        run.read(externalIds.length, null);
        List<FetchedPosting> postings = Arrays.stream(externalIds)
                .map(id -> new FetchedPosting(id, "Engineer " + id, "https://qworder.test/job/" + id, null, null))
                .toList();
        recorder.record(run, postings, Set.of(), Set.of(), Map.of());
    }

    private void confirmedDaysAgo(Source source, String externalId, int days) {
        jdbcTemplate.update("UPDATE job_posting SET last_confirmed_at = now() - make_interval(days => ?) "
                + "WHERE source_id = ? AND external_id = ?", days, source.getId(), externalId);
    }
}
