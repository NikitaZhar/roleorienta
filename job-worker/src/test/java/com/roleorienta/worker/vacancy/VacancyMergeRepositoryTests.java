package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Склейка по ссылке на вакансию у работодателя (§268), случай Accenture: публикация Workday
 * {@code …/accenturecareers/job/Bratislava/Business-Analyst_R00361539}, объявление общего списка портала с кнопкой
 * отклика на {@code …/sk-SK/AccentureCareers/job/…} и объявление, добранное с profesia.sk, со ссылкой в
 * {@code profesia_offer} — одна вакансия Workday: публикации переходят, отметка пользователя переходит (из «Viewed» и
 * «Applied» остаётся «Applied»), выдача переходит; объявление с другой ссылкой не трогается. Аудит §268 (§270): номер
 * вакансии в параметре (Taleo {@code jobdetail.ftl?job=N}) — склейка с той самой вакансией, а не с любой вакансией
 * доски; ключ, общий для нескольких публикаций, — не основание; другое название при той же ссылке — не основание
 * (§270); цепочек между проходами нет. Тест в транзакции с
 * откатом.
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

    @Autowired
    private VacancyMergeRepository merges;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobPostingRepository postings;

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

        assertThat(merges.mergeByEmployerLinks()).isEqualTo(2);

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
        assertThat(merges.mergeByEmployerLinks()).isZero();
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

        assertThat(merges.mergeByEmployerLinks()).isEqualTo(1);
        assertThat(merges.mergeByEmployerLinks()).isZero();

        assertThat(vacancyOf("profesia:9268011")).isEqualTo(second);
        assertThat(vacancyOf("taleo-1")).isEqualTo(first);
        assertThat(vacancyOf("taleo-3")).isEqualTo(third);
        assertThat(vacancyOf("profesia:9268012")).isEqualTo(sharedAd);
        assertThat(vacancyOf("shared-1")).isEqualTo(sharedOne);
        assertThat(vacancyOf("shared-2")).isEqualTo(sharedTwo);
        assertThat(vacancyOf("profesia:9268013")).isEqualTo(otherTitle);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vacancy WHERE id = ?", Integer.class, ad)).isZero();
    }

    /**
     * Объявление записано раньше публикации системы найма (доска подключена позже, §266): после склейки главная
     * публикация вакансии — Workday, объявление — присоединённое и её название и ссылку не меняет (§271, аудит §270
     * замечание 5).
     */
    @Test
    void earlierAdDoesNotBecomeMainPostingAfterMerge() {
        long portal = source("sluzbyzamestnanosti", "92680003");
        long workday = source("workday", "accenture.wd103.myworkdayjobs.com/accenturecareers");
        posting(portal, "profesia:9268021", "https://www.profesia.sk/O9268021", BUTTON);
        long kept = posting(workday, "/job/Bratislava/Business-Analyst_R00361539", WORKDAY, null);

        assertThat(merges.mergeByEmployerLinks()).isEqualTo(1);

        assertThat(vacancyOf("profesia:9268021")).isEqualTo(kept);
        assertThat(postings.findJoinedExternalIds(portal)).containsExactly("profesia:9268021");
        assertThat(postings.findJoinedExternalIds(workday)).isEmpty();
    }

    private long vacancyOf(String externalId) {
        return jdbcTemplate.queryForObject("SELECT vacancy_id FROM job_posting WHERE external_id = ?", Long.class,
                externalId);
    }

    private long source(String provider, String board) {
        return jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES (?, ?) RETURNING id", Long.class,
                provider, board);
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
