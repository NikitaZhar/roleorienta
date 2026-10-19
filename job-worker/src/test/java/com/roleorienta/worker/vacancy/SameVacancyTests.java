package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.crawl.CrawlRun;
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
 * Одна вакансия в двух источниках компании — одна запись (§139; бизнес-описание §4.3, сценарий 5): новая публикация
 * присоединяется к незакрытой вакансии другого источника той же компании по ссылке без схемы, {@code www.} и
 * {@code /} в конце или по провайдеру, хосту и внешнему id; без надёжного признака — своя вакансия.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SameVacancyTests {

    @Autowired
    private PostingRecorder recorder;

    @Autowired
    private SourceRepository sources;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long company;

    /**
     * Чистые публикации и вакансии (сначала ссылки на вакансии: соответствие позициям, выдача, отметки — их оставляют
     * другие тесты); компания теста {@code 99020001}.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "unsuitable_mark", "vacancy_position_match",
                "company_source", "source_snapshot", "vacancy_revision", "crawl_run", "job_posting", "vacancy",
                "outbox_event", "task", "source"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("DELETE FROM company WHERE registration_number LIKE '9902%'");
        company = jdbcTemplate.queryForObject("INSERT INTO company (country, registration_number, name, registry) "
                + "VALUES ('SK', '99020001', 'Qwsame s.r.o.', 'RPO') RETURNING id", Long.class);
    }

    /**
     * Одна доска подключена дважды (с {@code www.} и без, с {@code /} в конце и без): одна вакансия, две публикации;
     * вакансия с другой ссылкой — своя.
     */
    @Test
    void sameAddressJoinsVacancyOfAnotherSource() {
        Source first = source("jobposting", "https://qwsame.test/ponuka");
        Source second = source("jobposting", "https://www.qwsame.test/ponuka/");

        read(first, posting("https://qwsame.test/ponuka/brusic/", "https://qwsame.test/ponuka/brusic/"));
        read(second, posting("https://www.qwsame.test/ponuka/brusic", "http://www.qwsame.test/ponuka/brusic"),
                posting("https://www.qwsame.test/ponuka/zvarac", "https://www.qwsame.test/ponuka/zvarac"));

        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(2);
        assertThat(count("SELECT count(DISTINCT vacancy_id) FROM job_posting WHERE url LIKE '%brusic%'")).isEqualTo(1);
    }

    /**
     * Языковые разделы одной доски Phenom: тот же хост и внешний id — одна вакансия; другой хост — своя; повторное
     * чтение присоединённого раздела не меняет название и ссылку вакансии (их задаёт первая публикация, аудит §143).
     */
    @Test
    void sameProviderHostAndIdJoinsVacancy() {
        Source global = source("phenom", "careers.qwsame.test/global/en");
        Source local = source("phenom", "careers.qwsame.test/eu/sk");
        Source other = source("phenom", "jobs.qwother.test/sk");

        read(global, new FetchedPosting("AV-1", "Driver", "https://careers.qwsame.test/global/en/job/AV-1", null,
                null));
        read(local, new FetchedPosting("AV-1", "Vodič", "https://careers.qwsame.test/eu/sk/job/AV-1", null, null));
        read(other, new FetchedPosting("AV-1", "Driver", "https://jobs.qwother.test/sk/job/AV-1", null, null));

        assertThat(count("SELECT count(*) FROM job_posting")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(2);

        read(local, new FetchedPosting("AV-1", "Vodič", "https://careers.qwsame.test/eu/sk/job/AV-1", null, null));

        assertThat(jdbcTemplate.queryForObject("SELECT v.title || ' ' || v.primary_url FROM vacancy v "
                + "JOIN job_posting p ON p.vacancy_id = v.id WHERE p.source_id = ?", String.class, local.getId()))
                .isEqualTo("Driver https://careers.qwsame.test/global/en/job/AV-1");
    }

    /**
     * Источник другой компании с той же ссылкой — своя вакансия.
     */
    @Test
    void anotherCompanyKeepsOwnVacancy() {
        Source first = source("jobposting", "https://qwsame.test/ponuka");
        Source foreign = sources.save(new Source("jobposting", "https://qwsame.test/agentura"));

        read(first, posting("https://qwsame.test/ponuka/brusic", "https://qwsame.test/ponuka/brusic"));
        read(foreign, posting("https://qwsame.test/ponuka/brusic", "https://qwsame.test/ponuka/brusic"));

        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(2);
    }

    /** Публикация «Brúsič» — у всех одно название: название признаком не считается. */
    private static FetchedPosting posting(String externalId, String url) {
        return new FetchedPosting(externalId, "Brúsič", url, null, null);
    }

    private Source source(String provider, String board) {
        Source source = sources.save(new Source(provider, board));
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id) VALUES (?, ?)", company,
                source.getId());
        return source;
    }

    private void read(Source source, FetchedPosting... postings) {
        long taskId = jdbcTemplate.queryForObject("INSERT INTO task (type, task_key, payload, state) "
                + "VALUES ('READ_SOURCE', gen_random_uuid()::text, '{}', 'QUEUED') RETURNING id", Long.class);
        CrawlRun run = recorder.startRun(source, taskId);
        run.read(postings.length, null);
        recorder.record(run, List.of(postings), Set.of(), Set.of(), Map.of());
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
