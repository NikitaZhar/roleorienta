package com.roleorienta.worker.career;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Источник компании по ссылке на вакансию у работодателя (§266): кнопка отклика объявления общего списка портала
 * ведёт на Workday — компании объявления подключается доска Workday (случай Accenture); ссылка у объявления,
 * добранного с profesia.sk, — на SmartRecruiters, компания — {@code profesia_offer.company_id}; ссылка на собственный
 * сайт работодателя источника не даёт; повторный проход ничего не дублирует. Тест в транзакции с откатом.
 */
@SpringBootTest(properties = "app.employer-links.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class EmployerLinkSourcesTests {

    private static final String ACCENTURE_JOB =
            "https://accenture.wd103.myworkdayjobs.com/sk-SK/AccentureCareers/job/Bratislava/Business-Analyst_R00361539";

    @Autowired
    private EmployerLinkSources employerLinks;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void recruitingSystemLinkConnectsBoardToCompany() {
        long accenture = company("92660001", "Accenture, s.r.o.");
        long alza = company("92660002", "Alza.sk s. r. o.");
        long gamma = company("92660003", "Gamma s.r.o.");
        posting(accenture, ACCENTURE_JOB);
        posting(alza, "https://www.alza.sk/kariera/vacancy-123");
        jdbcTemplate.update("""
                INSERT INTO profesia_offer (num, employer, url, listed_at, company_id, employer_url)
                VALUES (9266001, 'gamma', 'https://www.profesia.sk/praca/gamma/O9266001', now(), ?,
                        'https://jobs.smartrecruiters.com/GammaSro/744000123')""", gamma);

        employerLinks.connectAll();
        employerLinks.connectAll();

        assertThat(boards(accenture)).containsExactly("workday accenture.wd103.myworkdayjobs.com/accenturecareers");
        assertThat(boards(gamma)).containsExactly("smartrecruiters gammasro");
        assertThat(boards(alza)).isEmpty();
    }

    private long company(String registrationNumber, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO company (country, registration_number, name, registry) VALUES ('SK', ?, ?, 'RPO')
                RETURNING id""", Long.class, registrationNumber, name);
    }

    /** Публикация источника портала компании со ссылкой на вакансию у работодателя. */
    private void posting(long company, String employerUrl) {
        long source = jdbcTemplate.queryForObject("""
                INSERT INTO source (provider, board) SELECT 'sluzbyzamestnanosti', registration_number FROM company
                WHERE id = ? RETURNING id""", Long.class, company);
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id, role) VALUES (?, ?, 'EMPLOYER')",
                company, source);
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', 'Business Analyst', 'https://www.profesia.sk/O1', now(), now()) RETURNING id""",
                Long.class);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at,
                                         employer_url, employer_link_checked)
                VALUES (?, ?, ?, 'Business Analyst', 'https://www.profesia.sk/O1', now(), now(), ?, TRUE)""",
                source, vacancy, "profesia:" + vacancy, employerUrl);
    }

    /** Доски, подключённые компании, — «провайдер доска». */
    private List<String> boards(long company) {
        return jdbcTemplate.queryForList("""
                SELECT s.provider || ' ' || s.board FROM company_source cs JOIN source s ON s.id = cs.source_id
                WHERE cs.company_id = ? AND s.provider <> 'sluzbyzamestnanosti' ORDER BY 1""", String.class, company);
    }
}
