package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Пересмотр «одна вакансия — одна запись» ({@link VacancyMergeRepository#mergeBatch}, §280). Склейка по ссылке из
 * кнопки отклика (§268), случай Accenture: публикация Workday {@code …/accenturecareers/job/Bratislava/
 * Business-Analyst_R00361539}, объявление общего списка портала с кнопкой на {@code …/sk-SK/AccentureCareers/job/…} и
 * объявление добора со ссылкой в {@code profesia_offer} — одна вакансия Workday: публикации, отметка пользователя (из
 * «Viewed» и «Applied» остаётся «Applied») и выдача переходят; объявление с другой ссылкой не трогается; номер вакансии
 * в параметре (Taleo {@code jobdetail.ftl?job=N}) — с той самой вакансией; общий ключ нескольких публикаций и другое
 * название — не основание (§270). Накопленные дубли: тот же номер объявления profesia.sk, название и место у
 * работодателя (в том числе копии одного источника); разные города, кнопка на другую вакансию и пары «не склеивать» —
 * не склеиваются; журнал склеек; размер партии. Тест в транзакции с откатом.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class VacancyMergeRepositoryTests {

    private static final String WORKDAY =
            "https://accenture.wd103.myworkdayjobs.com/accenturecareers/job/Bratislava/Business-Analyst_R00361539";
    private static final String BUTTON =
            "https://accenture.wd103.myworkdayjobs.com/sk-SK/AccentureCareers/job/Bratislava/Business-Analyst_R00361539";
    private static final String TALEO = "https://molgroup.taleo.net/careersection/external/jobdetail.ftl?job=";
    private static final int BATCH = 200;

    @Autowired
    private VacancyMergeRepository merges;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobPostingRepository postings;

    /**
     * Пересмотр идёт по всем открытым вакансиям базы: публикации и вакансии других тестов этого контекста (их не
     * откатывает, например, {@code SameVacancyTests}) склеились бы вместе с вакансиями теста — таблицы очищаются в
     * транзакции теста и после него возвращаются откатом.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"digest_vacancy", "delivered_vacancy", "vacancy_status_history",
                "vacancy_user_status", "vacancy_language", "vacancy_position_match", "vacancy_merge",
                "vacancy_merge_block", "company_source", "source_snapshot", "vacancy_revision", "crawl_run",
                "job_posting", "vacancy", "profesia_offer"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    @Test
    void adJoinsRecruitingSystemVacancyByEmployerLink() {
        long workday = source("workday", "accenture.wd103.myworkdayjobs.com/accenturecareers");
        long portal = source("sluzbyzamestnanosti", "92680001");
        long profesia = source("profesia", "accenture-test");
        long kept = posting(workday, "/job/Bratislava/Business-Analyst_R00361539", WORKDAY, null);
        long portalAd = posting(portal, "profesia:9268001", "https://www.profesia.sk/O9268001", BUTTON);
        long offerAd = posting(profesia, "profesia:9268002", "https://www.profesia.sk/praca/accenture/O9268002", null);
        jdbcTemplate.update("""
                INSERT INTO profesia_offer (num, employer, url, listed_at, employer_url)
                VALUES (9268002, 'accenture-test', 'https://www.profesia.sk/praca/accenture/O9268002', now(), ?)""",
                BUTTON);
        long other = posting(portal, "profesia:9268003", "https://www.profesia.sk/O9268003",
                "https://accenture.wd103.myworkdayjobs.com/sk-SK/AccentureCareers/job/Bratislava/Other_R1");
        long user = jdbcTemplate.queryForObject("INSERT INTO user_account (email, password_hash) "
                + "VALUES ('qwmerge@test', 'x') RETURNING id", Long.class);
        long condition = jdbcTemplate.queryForObject("INSERT INTO search_condition (user_id, countries, portion_limit, "
                + "active) VALUES (?, '{SK}', 10, TRUE) RETURNING id", Long.class, user);
        long pass = jdbcTemplate.queryForObject("INSERT INTO pass_run (window_start) VALUES (now() - interval '9 days') "
                + "RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO delivered_vacancy VALUES (?, ?, ?, now())", condition, portalAd, pass);
        jdbcTemplate.update("INSERT INTO vacancy_user_status VALUES (?, ?, 'VIEWED', now()), (?, ?, 'APPLIED', now() - "
                + "interval '1 day')", user, kept, user, portalAd);

        assertThat(merges.mergeBatch(BATCH)).isEqualTo(2);

        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT vacancy_id FROM job_posting WHERE source_id IN (?, ?, ?) "
                + "AND external_id <> 'profesia:9268003'", Long.class, workday, portal, profesia)).containsExactly(kept);
        assertThat(jdbcTemplate.queryForObject("SELECT vacancy_id FROM job_posting WHERE external_id = "
                + "'profesia:9268003'", Long.class)).isEqualTo(other);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vacancy WHERE id IN (?, ?)", Integer.class,
                portalAd, offerAd)).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT vacancy_id || ':' || status FROM vacancy_user_status "
                + "WHERE user_id = ?", String.class, user)).containsExactly(kept + ":APPLIED");
        assertThat(jdbcTemplate.queryForObject("SELECT vacancy_id FROM delivered_vacancy WHERE search_condition_id = ?",
                Long.class, condition)).isEqualTo(kept);
        assertThat(merges.mergeBatch(BATCH)).isZero();
    }

    @Test
    void numberInQueryAndSharedLinksAreRespected() {
        long taleo = source("taleo", "molgroup.taleo.net/external");
        long portal = source("sluzbyzamestnanosti", "92680002");
        long first = posting(taleo, "taleo-1", TALEO + "1&lang=en", null);
        long second = posting(taleo, "taleo-2", TALEO + "2&lang=en", null);
        long third = posting(taleo, "taleo-3", TALEO + "3&lang=en", null);
        long ad = posting(portal, "profesia:9268011", "https://www.profesia.sk/O9268011", TALEO + "2&lang=en");
        long careers = source("careerpage", "https://shared.test/kariera");
        long sharedOne = posting(careers, "shared-1", "https://shared.test/kariera", null);
        long sharedTwo = posting(careers, "shared-2", "https://shared.test/kariera/", null);
        long sharedAd = posting(portal, "profesia:9268012", "https://www.profesia.sk/O9268012",
                "https://shared.test/kariera");
        long otherTitle = posting(portal, "profesia:9268013", "https://www.profesia.sk/O9268013", TALEO + "3&lang=en");
        jdbcTemplate.update("UPDATE job_posting SET title = 'Compliance Specialist' WHERE external_id = 'profesia:9268013'");

        // ad -> вакансия Taleo №2 (кнопка); shared-2 -> shared-1: тот же адрес без «/» в конце (признак 2, §280)
        assertThat(merges.mergeBatch(BATCH)).isEqualTo(2);
        assertThat(merges.mergeBatch(BATCH)).isZero();

        assertThat(vacancyOf("profesia:9268011")).isEqualTo(second);
        assertThat(vacancyOf("taleo-1")).isEqualTo(first);
        assertThat(vacancyOf("taleo-3")).isEqualTo(third);
        assertThat(vacancyOf("profesia:9268012")).isEqualTo(sharedAd);
        assertThat(vacancyOf("shared-1")).isEqualTo(sharedOne);
        assertThat(vacancyOf("shared-2")).isEqualTo(sharedOne).isNotEqualTo(sharedTwo);
        assertThat(vacancyOf("profesia:9268013")).isEqualTo(otherTitle);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vacancy WHERE id = ?", Integer.class, ad)).isZero();
    }

    /**
     * Объявление записано раньше публикации системы найма (доска подключена позже, §266): склеиваются (пара — в обе
     * стороны, §280), остаётся вакансия с меньшим id — объявления; главная публикация — Workday: название и ссылка
     * вакансии — от неё, объявление их не меняет (§271, аудит §270 замечание 5).
     */
    @Test
    void earlierAdDoesNotBecomeMainPostingAfterMerge() {
        long portal = source("sluzbyzamestnanosti", "92680003");
        long workday = source("workday", "accenture.wd103.myworkdayjobs.com/accenturecareers");
        long kept = posting(portal, "profesia:9268021", "https://www.profesia.sk/O9268021", BUTTON);
        posting(workday, "/job/Bratislava/Business-Analyst_R00361539", WORKDAY, null);

        assertThat(merges.mergeBatch(BATCH)).isEqualTo(1);

        assertThat(vacancyOf("/job/Bratislava/Business-Analyst_R00361539")).isEqualTo(kept);
        assertThat(jdbcTemplate.queryForObject("SELECT primary_url FROM vacancy WHERE id = ?", String.class, kept))
                .isEqualTo(WORKDAY);
        assertThat(postings.findJoinedExternalIds(portal)).containsExactly("profesia:9268021");
        assertThat(postings.findJoinedExternalIds(workday)).isEmpty();
    }

    /**
     * Накопленные дубли (выборка s276): тот же номер объявления profesia.sk у портала и добора — одна вакансия; у
     * работодателя — то же название и место (кадровая страница «Senec» и портал «Senec - Senec»), копии одного списка;
     * «Vodič» в Martin и Žilina — разные; объявление, кнопка которого ведёт на другую вакансию работодателя (ZF Levice,
     * пара 99), по названию не склеивается. В журнале — признак, строка и отметки присоединённой.
     */
    @Test
    void storedDuplicatesMergeByNumberAndTitleWithJournal() {
        long company = jdbcTemplate.queryForObject("INSERT INTO company (country, registration_number, name, registry) "
                + "VALUES ('SK', '92689901', 'Qwmerge s.r.o.', 'RPO') RETURNING id", Long.class);
        long page = employerSource(company, "careerpage", "https://qwmerge.test/kariera");
        long portal = employerSource(company, "sluzbyzamestnanosti", "92689901");
        long offers = source("profesia", "qwmerge");
        long pageWarehouse = postingAt(page, "w1", "https://qwmerge.test/kariera/w1", "Skladník (m/ž)", "Senec");
        long portalWarehouse = postingAt(portal, "w2", "https://qwmerge.test/portal/w2", "Skladník (muž/žena)",
                "Senec - Senec");
        long portalCopy = postingAt(portal, "w3", "https://qwmerge.test/portal/w3", "Skladník (muž/žena)",
                "Senec - Senec");
        long martin = postingAt(page, "d1", "https://qwmerge.test/kariera/d1", "Vodič", "Martin");
        long zilina = postingAt(portal, "d2", "https://qwmerge.test/portal/d2", "Vodič", "Žilina - Žilina");
        long portalAd = postingAt(portal, "profesia:9268100", "https://www.profesia.sk/O9268100", "Operátor", "Senec");
        long offerAd = postingAt(offers, "profesia:9268100", "https://www.profesia.sk/praca/qwmerge/O9268100",
                "Operátor", null);
        long quality = postingAt(page, "q1", "https://qwmerge.test/kariera/q1", "Veduci timu kvality", "Levice");
        postingAt(page, "q2", "https://qwmerge.test/kariera/q2", "Veduci timu procesnej kvality", "Levice");
        long vetoed = postingAt(portal, "profesia:9268101", "https://www.profesia.sk/O9268101",
                "Veduci timu kvality (muž/žena)", "Levice - Levice");
        jdbcTemplate.update("UPDATE job_posting SET employer_url = 'https://qwmerge.test/kariera/q2' "
                + "WHERE external_id = 'profesia:9268101'");
        long user = jdbcTemplate.queryForObject("INSERT INTO user_account (email, password_hash) "
                + "VALUES ('qwmerge2@test', 'x') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO vacancy_user_status VALUES (?, ?, 'APPLIED', now())", user, portalWarehouse);

        assertThat(merges.mergeBatch(BATCH)).isEqualTo(3);
        assertThat(merges.mergeBatch(BATCH)).isZero();

        assertThat(vacancyOf("w2")).isEqualTo(pageWarehouse);
        assertThat(vacancyOf("w3")).isEqualTo(pageWarehouse);
        assertThat(jdbcTemplate.queryForObject("SELECT count(DISTINCT vacancy_id) FROM job_posting "
                + "WHERE external_id = 'profesia:9268100'", Integer.class)).isEqualTo(1);
        assertThat(vacancyOf("d2")).isEqualTo(zilina).isNotEqualTo(martin);
        assertThat(vacancyOf("profesia:9268101")).isEqualTo(vetoed).isNotEqualTo(quality);
        assertThat(jdbcTemplate.queryForList("SELECT signal || ':' || merged_vacancy_id FROM vacancy_merge "
                + "ORDER BY merged_vacancy_id", String.class)).containsExactly("title_place:" + portalWarehouse,
                "title_place:" + portalCopy, "profesia_number:" + Math.max(portalAd, offerAd));
        assertThat(jdbcTemplate.queryForObject("SELECT user_marks -> 0 ->> 'status' FROM vacancy_merge "
                + "WHERE merged_vacancy_id = ?", String.class, portalWarehouse)).isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForObject("SELECT (merged_vacancy ->> 'id')::bigint FROM vacancy_merge "
                + "WHERE merged_vacancy_id = ?", Long.class, portalCopy)).isEqualTo(portalCopy);
    }

    /**
     * Пара «не склеивать» (разделена вручную, {@code vacancy_merge_block}) — не склеивается; размер партии соблюдается.
     */
    @Test
    void blockedPairIsKeptApartAndBatchIsLimited() {
        long careers = source("careerpage", "https://qwblock.test/kariera");
        long first = posting(careers, "b1", "https://qwblock.test/kariera/1", null);
        long second = posting(careers, "b2", "https://qwblock.test/kariera/1/", null);
        long third = posting(careers, "b3", "https://qwblock.test/kariera/3", null);
        long fourth = posting(careers, "b4", "https://qwblock.test/kariera/3/", null);
        jdbcTemplate.update("INSERT INTO vacancy_merge_block (vacancy_a, vacancy_b) VALUES (?, ?)", first, second);

        assertThat(merges.mergeBatch(1)).isEqualTo(1);
        assertThat(merges.mergeBatch(1)).isZero();

        assertThat(vacancyOf("b2")).isEqualTo(second);
        assertThat(vacancyOf("b4")).isEqualTo(third).isNotEqualTo(fourth);
    }

    private long vacancyOf(String externalId) {
        return jdbcTemplate.queryForObject("SELECT vacancy_id FROM job_posting WHERE external_id = ?", Long.class,
                externalId);
    }

    private long source(String provider, String board) {
        return jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES (?, ?) RETURNING id", Long.class,
                provider, board);
    }

    private long employerSource(long company, String provider, String board) {
        long source = source(provider, board);
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id, role) VALUES (?, ?, 'EMPLOYER')",
                company, source);
        return source;
    }

    /** Публикация с названием и местом в своей вакансии; возвращает id вакансии. */
    private long postingAt(long source, String externalId, String url, String title, String location) {
        long vacancy = jdbcTemplate.queryForObject("INSERT INTO vacancy (state, title, primary_url, first_seen_at, "
                + "last_confirmed_at) VALUES ('ACTIVE', ?, ?, now(), now()) RETURNING id", Long.class, title, url);
        jdbcTemplate.update("INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, location, "
                + "first_seen_at, last_confirmed_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())", source, vacancy,
                externalId, title, url, location);
        return vacancy;
    }

    /** Публикация в своей вакансии; возвращает id вакансии. */
    private long posting(long source, String externalId, String url, String employerUrl) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', 'Business Analyst', ?, now(), now()) RETURNING id""", Long.class, url);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at,
                                         employer_url)
                VALUES (?, ?, ?, 'Business Analyst', ?, now(), now(), ?)""", source, vacancy, externalId, url,
                employerUrl);
        return vacancy;
    }
}
