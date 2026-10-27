package com.roleorienta.worker.occupation;

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
 * Профессия по названию (§215): голова названия выучивается по публикациям портала с кодом (не меньше двух, доля одной
 * группы не меньше 70 %); у публикации без кода — выученная голова, иначе однозначный синоним ESCO, иначе нет;
 * неоднозначная голова не выучивается; код портала не трогается. Тест в транзакции с откатом: общая БД не меняется.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class TitleOccupationRepositoryTests {

    @Autowired
    private TitleOccupationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void learnsHeadsFromPortalCodesAndRecognizesOthers() {
        long source = jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES ('careerpage', "
                + "'title-test') RETURNING id", Long.class);
        posting(source, "Kuchár/ka (muž/žena)", "5120");
        posting(source, "KUCHÁR - nástup ihneď", "5120");
        posting(source, "Operátor (muž/žena)", "8211");
        posting(source, "Operátor", "4222");
        long cook = posting(source, "Kuchár, Hotel Tatra", null);
        long unknown = posting(source, "Zxqw - zmena A", null);
        long driver = posting(source, "vodič nákladného vozidla", null);
        long coded = posting(source, "Kuchár", "5120");

        repository.relearn();

        assertThat(jdbcTemplate.queryForObject("SELECT code FROM title_occupation WHERE head = 'kuchar'", String.class))
                .isEqualTo("5120");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM title_occupation WHERE head = 'operator'",
                Integer.class)).isZero();
        assertThat(recognized(cook)).isEqualTo("5120");
        assertThat(recognized(unknown)).isNull();
        assertThat(recognized(driver)).isEqualTo("8332");
        assertThat(recognized(coded)).isNull();
    }

    private long posting(long source, String title, String code) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', ?, 'https://title.example', now(), now()) RETURNING id""", Long.class, title);
        return jdbcTemplate.queryForObject("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at,
                                         occupation_code)
                VALUES (?, ?, ?, ?, 'https://title.example', now(), now(), ?) RETURNING id""", Long.class,
                source, vacancy, "title-" + vacancy, title, code);
    }

    private String recognized(long posting) {
        return jdbcTemplate.queryForObject("SELECT title_occupation_code FROM job_posting WHERE id = ?", String.class,
                posting);
    }
}
