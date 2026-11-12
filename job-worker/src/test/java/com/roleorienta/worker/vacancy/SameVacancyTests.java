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
 * Одна вакансия — одна запись при записи чтения (§139, §260; одно определение «та же вакансия» —
 * docs/vacancy-identity-plan.md §4, стенограмма §279): новая публикация присоединяется к незакрытой вакансии по
 * сильному признаку у всех публикаций — номер объявления profesia.sk, адрес без сессионных параметров, провайдер + хост
 * + внешний id (у Workday — и без суффикса копии при том же названии); иначе у работодателя по названию и месту, в том
 * числе в своём источнике: то же название и место, или место только у одной при одной вакансии этого названия; разные
 * места и сомнение — свои вакансии; объявление profesia.sk по названию при записи не присоединяется.
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
     * Сценарий 5 (бизнес-описание §7.4-А): одна вакансия в двух источниках — одна запись; пропала из одного источника
     * (три полных чтения без неё — её публикация закрыта), а другой её подтверждает — вакансия не закрыта; пропала и из
     * второго — закрыта.
     */
    @Test
    void scenario5VacancyOfTwoSourcesClosesOnlyWhenGoneFromBoth() {
        Source first = source("jobposting", "https://qwsame.test/ponuka");
        Source second = source("jobposting", "https://www.qwsame.test/ponuka/");
        read(first, posting("https://qwsame.test/ponuka/brusic/", "https://qwsame.test/ponuka/brusic/"));
        read(second, posting("https://www.qwsame.test/ponuka/brusic", "https://www.qwsame.test/ponuka/brusic"));
        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(1);

        for (int reads = 0; reads < 3; reads++) {
            read(first);
            read(second, posting("https://www.qwsame.test/ponuka/brusic", "https://www.qwsame.test/ponuka/brusic"));
        }
        assertThat(count("SELECT count(*) FROM job_posting WHERE closed_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM vacancy WHERE state = 'ACTIVE'")).isEqualTo(1);

        for (int reads = 0; reads < 3; reads++) {
            read(second);
        }
        assertThat(count("SELECT count(*) FROM vacancy WHERE state = 'CLOSED'")).isEqualTo(1);
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
     * Та же ссылка у источника другой компании — та же страница, та же вакансия (сильный признак — без отбора по
     * работодателю, §279; прежде — своя вакансия, §139); другая ссылка — своя.
     */
    @Test
    void sameLinkInSourceOfAnotherCompanyIsSameVacancy() {
        Source first = source("jobposting", "https://qwsame.test/ponuka");
        Source foreign = sources.save(new Source("jobposting", "https://qwsame.test/agentura"));

        read(first, posting("https://qwsame.test/ponuka/brusic", "https://qwsame.test/ponuka/brusic"));
        read(foreign, posting("https://qwsame.test/ponuka/brusic", "https://qwsame.test/ponuka/brusic"),
                posting("https://qwsame.test/agentura/zvarac", "https://qwsame.test/agentura/zvarac"));

        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(2);
        assertThat(count("SELECT count(DISTINCT vacancy_id) FROM job_posting WHERE url LIKE '%brusic'")).isEqualTo(1);
    }

    /**
     * Тот же номер объявления profesia.sk — одна вакансия у любых источников и хостов: объявление общего списка
     * портала, добор с profesia.sk (без компании) и кадровая страница на {@code *.profesia.sk} (§279, пары 155–160
     * выборки s276); другой номер — своя.
     */
    @Test
    void sameProfesiaNumberJoinsAcrossProvidersAndHosts() {
        Source portal = source("sluzbyzamestnanosti", "99020001");
        Source offers = sources.save(new Source("profesia", "qwsame"));
        Source page = source("careerpage", "https://qwsame.profesia.sk/praca/qwsame");

        read(portal, new FetchedPosting("profesia:9902001", "Skladník (muž/žena)", "https://www.profesia.sk/O9902001",
                "Senec - Senec", null));
        read(offers, new FetchedPosting("profesia:9902001", "Skladník", "https://www.profesia.sk/praca/qwsame/O9902001",
                null, null), new FetchedPosting("profesia:9902002", "Skladník", "https://www.profesia.sk/O9902002",
                null, null));
        read(page, new FetchedPosting("https://qwsame.profesia.sk/praca/qwsame/O9902001", "Skladník",
                "https://qwsame.profesia.sk/praca/qwsame/O9902001", "Slovakia", null));

        assertThat(count("SELECT count(DISTINCT vacancy_id) FROM job_posting WHERE url LIKE '%O9902001'"))
                .isEqualTo(1);
        assertThat(vacancyOf("profesia:9902002"))
                .isNotEqualTo(vacancyOf("https://qwsame.profesia.sk/praca/qwsame/O9902001"));
    }

    /**
     * Сессионный параметр в адресе ({@code search_id} кадровой страницы) — та же публикация того же источника: новый
     * внешний id без параметра присоединяется к вакансии прежнего (§279).
     */
    @Test
    void addressWithoutSessionParameterJoinsVacancyOfOwnSource() {
        Source page = source("careerpage", "https://qwsame.test/kariera");

        read(page, posting("https://qwsame.test/kariera/1?search_id=a", "https://qwsame.test/kariera/1?search_id=a"));
        read(page, posting("https://qwsame.test/kariera/1", "https://qwsame.test/kariera/1"));

        assertThat(vacancyOf("https://qwsame.test/kariera/1")).isEqualTo(vacancyOf(
                "https://qwsame.test/kariera/1?search_id=a"));
    }

    /**
     * Копия вакансии на другом сайте Workday того же хоста — внешний id с суффиксом {@code -N} и то же название — одна
     * вакансия (HPE, пары 75–79); с другим названием — своя.
     */
    @Test
    void workdayCopyWithSuffixJoinsWhenTitleIsSame() {
        Source first = sources.save(new Source("workday", "qwsame.wd5.myworkdayjobs.com/acjobsite"));
        Source second = sources.save(new Source("workday", "qwsame.wd5.myworkdayjobs.com/wfm"));

        read(first, new FetchedPosting("/job/Sofia/Architect_1209955", "AI Architect",
                "https://qwsame.wd5.myworkdayjobs.com/acjobsite/job/Sofia/Architect_1209955", null, null),
                new FetchedPosting("/job/Sofia/Lead_1209956", "Lead",
                        "https://qwsame.wd5.myworkdayjobs.com/acjobsite/job/Sofia/Lead_1209956", null, null));
        read(second, new FetchedPosting("/job/Sofia/Architect_1209955-1", "AI Architect",
                "https://qwsame.wd5.myworkdayjobs.com/wfm/job/Sofia/Architect_1209955-1", null, null),
                new FetchedPosting("/job/Sofia/Lead_1209956-1", "Lead Engineer",
                        "https://qwsame.wd5.myworkdayjobs.com/wfm/job/Sofia/Lead_1209956-1", null, null));

        assertThat(vacancyOf("/job/Sofia/Architect_1209955-1")).isEqualTo(vacancyOf("/job/Sofia/Architect_1209955"));
        assertThat(vacancyOf("/job/Sofia/Lead_1209956-1")).isNotEqualTo(vacancyOf("/job/Sofia/Lead_1209956"));
    }

    /**
     * Объявление profesia.sk при записи по названию и месту не присоединяется: ссылка из кнопки отклика читается позже и
     * может вести на другую вакансию (ZF Levice, пара 99); их склеит пересмотр (план §6, срез 3).
     */
    @Test
    void profesiaAdIsNotJoinedByTitleAtRecording() {
        Source page = source("careerpage", "https://qwsame.test/kariera");
        Source portal = source("sluzbyzamestnanosti", "99020001");

        read(page, located("p1", "Veduci timu zákaznickej kvality (m/f/d)", "Levice, NI, SK, 934 01"));
        read(portal, new FetchedPosting("profesia:9902099", "Veduci timu zákaznickej kvality (m/f/d) (muž/žena)",
                "https://www.profesia.sk/O9902099", "Levice - Levice", null));

        assertThat(vacancyOf("profesia:9902099")).isNotEqualTo(vacancyOf("p1"));
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
     * названия и место одного списка (два «Operátor výroby» портала) — одна вакансия (решение владельца 2026-11-09,
     * §279; прежде — разные, §260).
     */
    @Test
    void doubtfulPostingKeepsOwnVacancyButCopiesJoin() {
        Source page = source("careerpage", "https://qwsame.test/kariera");
        Source portal = source("sluzbyzamestnanosti", "99020001");

        read(page, located("p1", "Čašník", "Martin"), located("p2", "Čašník", "Žilina"),
                located("p3", "Operátor výroby", null));
        read(portal, located("s1", "Čašník", null), located("s2", "Operátor výroby", null),
                located("s3", "Operátor výroby", null));

        assertThat(vacancyOf("s1")).isNotIn(vacancyOf("p1"), vacancyOf("p2"));
        assertThat(vacancyOf("s2")).isEqualTo(vacancyOf("p3"));
        assertThat(vacancyOf("s3")).isEqualTo(vacancyOf("p3"));
        assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(4);
    }

    /**
     * Работодатель вне реестра (§259): у источников без компании название работодателя не различает работодателей
     * (школы с одинаковым названием, выборка s276) — по названию и месту не присоединяется (§279; прежде — по
     * названию работодателя), только по сильному признаку (тот же номер profesia.sk).
     */
    @Test
    void employerWithoutCompanyJoinsOnlyByStrongSignal() {
        Source portal = sources.save(new Source("sluzbyzamestnanosti", "50393731"));
        Source profesia = sources.save(new Source("profesia", "martin-gaza"));
        Source other = sources.save(new Source("profesia", "iny"));
        jdbcTemplate.update("UPDATE source SET employer_name = ? WHERE id = ?", "Martin Gaža", portal.getId());
        jdbcTemplate.update("UPDATE source SET employer_name = ? WHERE id = ?", "MARTIN GAŽA", profesia.getId());
        jdbcTemplate.update("UPDATE source SET employer_name = ? WHERE id = ?", "Iný s.r.o.", other.getId());

        read(portal, located("s1", "Murár", "Senec"), new FetchedPosting("profesia:9902050", "Tesár (muž/žena)",
                "https://www.profesia.sk/O9902050", "Senec - Senec", null));
        read(profesia, located("f1", "Murár (muž/žena)", "Senec"), new FetchedPosting("profesia:9902050", "Tesár",
                "https://www.profesia.sk/praca/martin-gaza/O9902050", null, null));
        read(other, located("o1", "Murár", "Senec"));

        assertThat(vacancyOf("f1")).isNotEqualTo(vacancyOf("s1"));
        assertThat(vacancyOf("o1")).isNotEqualTo(vacancyOf("s1"));
        assertThat(count("SELECT count(DISTINCT vacancy_id) FROM job_posting WHERE external_id = 'profesia:9902050'"))
                .isEqualTo(1);
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
