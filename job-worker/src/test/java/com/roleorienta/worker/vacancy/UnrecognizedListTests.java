package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
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
 * Своя кадровая страница перестала давать список (§287): чтение «список не распознан» без публикаций засчитывает
 * отсутствие, публикации источника закрываются после пяти таких чтений подряд (§285: меню, товары и статьи прежних
 * правил держались открытыми вечно); список снова распознан — публикация с вакансией открывается; другое неполное
 * чтение (отказ страницы) отсутствие не засчитывает.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UnrecognizedListTests {

    private static final FetchedPosting KITCHEN = new FetchedPosting("https://qwunrec.test/kariera/kuchar", "Kuchár",
            "https://qwunrec.test/kariera/kuchar", null, null);
    private static final FetchedPosting MENU = new FetchedPosting("https://qwunrec.test/kariera/benefity", "Benefity",
            "https://qwunrec.test/kariera/benefity", null, null);

    @Autowired
    private PostingRecorder recorder;

    @Autowired
    private SourceRepository sources;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Source source;

    /**
     * Чистые публикации и вакансии (сначала ссылки на вакансии — их оставляют другие тесты); один источник.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "vacancy_status_history", "vacancy_user_status",
                "vacancy_language", "vacancy_position_match", "company_source", "source_snapshot", "vacancy_revision",
                "crawl_run", "job_posting", "vacancy", "outbox_event", "task", "source"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        source = sources.save(new Source("careerpage", "https://qwunrec.test/kariera"));
    }

    /** Пять чтений «не распознан» подряд — публикации закрыты; четыре — ещё открыты. */
    @Test
    void unrecognizedReadsCloseKnownPostings() {
        read(null, KITCHEN, MENU);

        for (int round = 1; round <= 4; round++) {
            read(PartialReason.LIST_NOT_RECOGNIZED);
        }
        assertThat(count("SELECT count(*) FROM job_posting WHERE closed_at IS NULL")).isEqualTo(2);
        assertThat(count("SELECT max(missing_complete_reads) FROM job_posting")).isEqualTo(4);

        read(PartialReason.LIST_NOT_RECOGNIZED);

        assertThat(count("SELECT count(*) FROM job_posting WHERE closed_at IS NULL")).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT state FROM vacancy", String.class))
                .containsExactly("CLOSED");
    }

    /** Список снова распознан — вакансия на странице открывается, пункт меню, которого больше нет, — закрыт. */
    @Test
    void recognizedListReopensVacancy() {
        read(null, KITCHEN, MENU);
        for (int round = 1; round <= 5; round++) {
            read(PartialReason.LIST_NOT_RECOGNIZED);
        }

        read(null, KITCHEN);

        assertThat(jdbcTemplate.queryForList("SELECT p.title || ':' || (p.closed_at IS NULL) || ':' || v.state "
                + "FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id ORDER BY 1", String.class))
                .containsExactly("Benefity:false:CLOSED", "Kuchár:true:ACTIVE");
    }

    /** Отказ страницы списка (неполное чтение по другой причине) — отсутствие не засчитывается. */
    @Test
    void otherPartialReadDoesNotCountAbsence() {
        read(null, KITCHEN);
        for (int round = 1; round <= 6; round++) {
            read(PartialReason.PAGE_FAILED);
        }

        assertThat(count("SELECT count(*) FROM job_posting WHERE closed_at IS NULL")).isEqualTo(1);
        assertThat(count("SELECT max(missing_complete_reads) FROM job_posting")).isZero();
    }

    private void read(PartialReason reason, FetchedPosting... postings) {
        long taskId = jdbcTemplate.queryForObject("INSERT INTO task (type, task_key, payload, state) "
                + "VALUES ('READ_SOURCE', gen_random_uuid()::text, '{}', 'QUEUED') RETURNING id", Long.class);
        CrawlRun run = recorder.startRun(source, taskId);
        run.read(postings.length, reason);
        recorder.record(run, List.of(postings), Set.of(), Set.of(), Map.of());
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
