package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

/**
 * Одна вакансия в двух источниках компании — одна запись (§139; бизнес-описание §4.3, сценарий 5): новая публикация
 * присоединяется к незакрытой вакансии другого источника той же компании по ссылке без схемы, {@code www.} и
 * {@code /} в конце или по провайдеру, хосту и внешнему id; иначе по названию и месту (§260): то же название и
 * место, или место только у одной; разные места, сомнение и одинаковые названия одного списка — свои вакансии.
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

    @Autowired
    private DataSource dataSource;

    private long company;

    /**
     * Чистые публикации и вакансии (сначала ссылки на вакансии: соответствие позициям, выдача, отметки — их оставляют
     * другие тесты); компания теста {@code 99020001}.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "vacancy_status_history", "vacancy_user_status",
                "vacancy_language", "vacancy_position_match", "company_source", "source_snapshot", "vacancy_revision",
                "crawl_run", "job_posting", "vacancy", "outbox_event", "task", "source"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("DELETE FROM search_condition WHERE user_id IN (SELECT id FROM user_account "
                + "WHERE email LIKE 'qwsame%')");
        jdbcTemplate.update("DELETE FROM user_account WHERE email LIKE 'qwsame%'");
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
                new FetchedPosting("https://www.qwsame.test/ponuka/zvarac", "Zvárač", "https://www.qwsame.test/ponuka/zvarac",
                        null, null));

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
        read(other, new FetchedPosting("AV-1", "Courier", "https://jobs.qwother.test/sk/job/AV-1", null, null));

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

    /**
     * Кадровая страница и портал: «Skladník (m/ž)» и «Skladník – 1 200 € brutto» в Bratislava — одна вакансия
     * (название без пометки пола и зарплаты); «Vodič» в Martin и Žilina — разные; «Kuchár» с местом и без — одна.
     */
    @Test
    void sameTitleAndPlaceJoinVacancyOfAnotherSource() {
        Source page = source("careerpage", "https://qwsame.test/kariera");
        Source portal = source("sluzbyzamestnanosti", "99020001");

        read(page, located("p1", "Skladník (m/ž)", "Bratislava"), located("p2", "Vodič", "Martin"),
                located("p3", "Kuchár", "Košice"));
        read(portal, located("s1", "Skladník – 1 200 € brutto", "Bratislava"), located("s2", "Vodič", "Žilina"),
                located("s3", "Kuchár", null));

        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(4);
        assertThat(vacancyOf("s1")).isEqualTo(vacancyOf("p1"));
        assertThat(vacancyOf("s2")).isNotEqualTo(vacancyOf("p2"));
        assertThat(vacancyOf("s3")).isEqualTo(vacancyOf("p3"));
        assertThat(jdbcTemplate.queryForObject("SELECT match_version LIKE 'reset:%' FROM vacancy WHERE id = ?",
                Boolean.class, vacancyOf("p1"))).isTrue();
    }

    /**
     * Сомнение — своя вакансия: публикация без места, когда у компании это название в двух местах; одинаковые
     * названия одного списка (два «Operátor výroby» портала) — к одной вакансии присоединяется только первое.
     */
    @Test
    void doubtfulPostingKeepsOwnVacancy() {
        Source page = source("careerpage", "https://qwsame.test/kariera");
        Source portal = source("sluzbyzamestnanosti", "99020001");

        read(page, located("p1", "Čašník", "Martin"), located("p2", "Čašník", "Žilina"),
                located("p3", "Operátor výroby", null));
        read(portal, located("s1", "Čašník", null), located("s2", "Operátor výroby", null),
                located("s3", "Operátor výroby", null));

        assertThat(vacancyOf("s1")).isNotIn(vacancyOf("p1"), vacancyOf("p2"));
        assertThat(vacancyOf("s2")).isEqualTo(vacancyOf("p3"));
        assertThat(vacancyOf("s3")).isNotEqualTo(vacancyOf("p3"));
        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(5);
    }

    /**
     * Работодатель вне реестра (§259): источник портала без компании и источник profesia.sk без компании с тем же
     * названием работодателя (без регистра) — одна вакансия; другой работодатель — своя.
     */
    @Test
    void employerWithoutCompanyJoinsByName() {
        Source portal = sources.save(new Source("sluzbyzamestnanosti", "50393731"));
        Source profesia = sources.save(new Source("profesia", "martin-gaza"));
        Source other = sources.save(new Source("profesia", "iny"));
        jdbcTemplate.update("UPDATE source SET employer_name = ? WHERE id = ?", "Martin Gaža", portal.getId());
        jdbcTemplate.update("UPDATE source SET employer_name = ? WHERE id = ?", "MARTIN GAŽA", profesia.getId());
        jdbcTemplate.update("UPDATE source SET employer_name = ? WHERE id = ?", "Iný s.r.o.", other.getId());

        read(portal, located("s1", "Murár", "Senec"));
        read(profesia, located("f1", "Murár (muž/žena)", "Senec"));
        read(other, located("o1", "Murár", "Senec"));

        assertThat(vacancyOf("f1")).isEqualTo(vacancyOf("s1"));
        assertThat(vacancyOf("o1")).isNotEqualTo(vacancyOf("s1"));
    }

    /**
     * Склейка накопленных дублей миграцией V92 (файл выполняется ещё раз): вакансия портала, записанная до связи
     * источника с компанией, склеивается с вакансией кадровой страницы того же названия и места; на оставшуюся
     * переходят публикации, выдача и отметка пользователя — из «Viewed» и «Applied» остаётся «Applied»; вакансия
     * другого места не трогается.
     */
    @Test
    void migrationMergesStoredDuplicatesWithUserMarks() {
        Source page = source("careerpage", "https://qwsame.test/kariera");
        Source portal = sources.save(new Source("sluzbyzamestnanosti", "99020001"));
        read(page, located("p1", "Skladník (m/ž)", "Bratislava"), located("p2", "Vodič", "Martin"));
        read(portal, located("s1", "Skladník", "Bratislava"), located("s2", "Vodič", "Žilina"));
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id) VALUES (?, ?)", company, portal.getId());
        long kept = vacancyOf("p1");
        long merged = vacancyOf("s1");
        long user = jdbcTemplate.queryForObject("INSERT INTO user_account (email, password_hash) "
                + "VALUES ('qwsame@test', 'x') RETURNING id", Long.class);
        long condition = jdbcTemplate.queryForObject("INSERT INTO search_condition (user_id, countries, portion_limit, "
                + "active) VALUES (?, '{SK}', 10, TRUE) RETURNING id", Long.class, user);
        long pass = jdbcTemplate.queryForObject("INSERT INTO pass_run (window_start) VALUES (now() - interval '9 days') "
                + "RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO delivered_vacancy VALUES (?, ?, ?, now())", condition, merged, pass);
        jdbcTemplate.update("INSERT INTO vacancy_user_status VALUES (?, ?, 'VIEWED', now()), (?, ?, 'APPLIED', now() - "
                + "interval '1 day')", user, kept, user, merged);

        new ResourceDatabasePopulator(new FileSystemResource(
                "../job-api/src/main/resources/db/migration/V92__same_vacancy.sql")).execute(dataSource);

        assertThat(vacancyOf("s1")).isEqualTo(kept);
        assertThat(vacancyOf("s2")).isNotEqualTo(vacancyOf("p2"));
        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT vacancy_id FROM delivered_vacancy WHERE search_condition_id = ?",
                Long.class, condition)).isEqualTo(kept);
        assertThat(jdbcTemplate.queryForList("SELECT vacancy_id || ':' || status FROM vacancy_user_status "
                + "WHERE user_id = ?", String.class, user)).containsExactly(kept + ":APPLIED");
    }

    /** Публикация «Brúsič» по адресу и внешнему id. */
    private static FetchedPosting posting(String externalId, String url) {
        return new FetchedPosting(externalId, "Brúsič", url, null, null);
    }

    private static FetchedPosting located(String externalId, String title, String place) {
        return new FetchedPosting(externalId, title, "https://qwsame.test/" + externalId, place, null);
    }

    private long vacancyOf(String externalId) {
        return jdbcTemplate.queryForObject("SELECT vacancy_id FROM job_posting WHERE external_id = ?", Long.class,
                externalId);
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
