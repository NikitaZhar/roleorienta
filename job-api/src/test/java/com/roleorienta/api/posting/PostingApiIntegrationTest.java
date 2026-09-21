package com.roleorienta.api.posting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roleorienta.api.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Интеграционный тест REST-чтения публикаций (§7) на реальном PostgreSQL (Testcontainers,
 * схема — Flyway) через MockMvc. Проверяет то, что не видит юнит-тест сервиса: маршруты и
 * JSON, курсорную пагинацию и фильтры на настоящем SQL, вложенные языки/навыки в карточке
 * и тело ошибки {@code application/problem+json} при {@code 404}.
 *
 * <p>Данные засеваются напрямую в базу (в API-приложении нет операций записи): провайдер
 * и источник — ради внешнего ключа публикации, затем три публикации с разными
 * нормализованными полями и требования для карточки. Публикации A/B/C вставляются по
 * порядку, поэтому их {@code id} возрастают (курсор ленты идёт по {@code id}).</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostingApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long idB;
    private long cardId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // saved_posting ссылается на job_posting (V16) — очищаем до публикаций.
        jdbcTemplate.update("DELETE FROM saved_posting");
        jdbcTemplate.update("DELETE FROM posting_skill");
        jdbcTemplate.update("DELETE FROM posting_language");
        jdbcTemplate.update("DELETE FROM posting_report");
        jdbcTemplate.update("DELETE FROM job_posting");
        jdbcTemplate.update("DELETE FROM source");
        jdbcTemplate.update("DELETE FROM provider");

        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (code, display_name, kind) VALUES ('greenhouse','Greenhouse','ATS') RETURNING id",
                Long.class);
        Long sourceId = jdbcTemplate.queryForObject(
                "INSERT INTO source (provider_id, kind, external_ref, base_url, state) "
                        + "VALUES (?, 'COMPANY_BOARD', 'acme', 'http://stub', 'ACTIVE') RETURNING id",
                Long.class, providerId);

        long idA = jdbcTemplate.queryForObject(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at, "
                        + "city, country, work_modality, salary_max, salary_currency, seniority, experience_years_min) "
                        + "VALUES (?, 'A', 'http://stub/A', 'Posting A', now(), now(), "
                        + "'Berlin', 'Germany', 'UNKNOWN', 110000, 'EUR', 'SENIOR', 5) RETURNING id",
                Long.class, sourceId);
        idB = jdbcTemplate.queryForObject(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at, "
                        + "city, country, work_modality, salary_max, salary_currency, seniority, experience_years_min) "
                        + "VALUES (?, 'B', 'http://stub/B', 'Posting B', now(), now(), "
                        + "NULL, 'EU', 'REMOTE', 95000, 'EUR', 'UNKNOWN', 3) RETURNING id",
                Long.class, sourceId);
        jdbcTemplate.update(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at, "
                        + "city, country, work_modality, salary_max, salary_currency, seniority, experience_years_min) "
                        + "VALUES (?, 'C', 'http://stub/C', 'Posting C', now(), now(), "
                        + "'Munich', 'Germany', 'UNKNOWN', 115000, 'EUR', 'MEDIOR', NULL)",
                sourceId);

        jdbcTemplate.update(
                "INSERT INTO posting_language (job_posting_id, language_code, mentioned, modality, extraction_version) "
                        + "VALUES (?, 'en', 'YES', 'REQUIRED', 'lang-rules-1')", idA);
        jdbcTemplate.update(
                "INSERT INTO posting_skill (job_posting_id, skill, stance, modality, extraction_version) "
                        + "VALUES (?, 'Java', 'REQUESTED', 'UNSPECIFIED', 'skill-rules-2')", idA);
        jdbcTemplate.update(
                "INSERT INTO posting_skill (job_posting_id, skill, stance, modality, extraction_version) "
                        + "VALUES (?, 'C#', 'NEGATED', 'UNSPECIFIED', 'skill-rules-2')", idA);
        this.cardId = idA;
    }


    @Test
    void feedReturnsAllPostingsWithoutCursorWhenFewer() throws Exception {
        mockMvc.perform(get("/api/v1/postings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].externalId").value("A"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void feedPaginatesByCursor() throws Exception {
        mockMvc.perform(get("/api/v1/postings").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value((int) idB));

        mockMvc.perform(get("/api/v1/postings").param("limit", "2").param("cursor", String.valueOf(idB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].externalId").value("C"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void filterByWorkModality() throws Exception {
        mockMvc.perform(get("/api/v1/postings").param("workModality", "REMOTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].externalId").value("B"));
    }

    @Test
    void filterByCountryCaseInsensitive() throws Exception {
        mockMvc.perform(get("/api/v1/postings").param("country", "germany"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void filterBySeniority() throws Exception {
        mockMvc.perform(get("/api/v1/postings").param("seniority", "SENIOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].externalId").value("A"));
    }

    @Test
    void filterByMinSalaryUsesUpperBound() throws Exception {
        // A(110000) и C(115000) проходят; B(95000) отсекается.
        mockMvc.perform(get("/api/v1/postings").param("minSalary", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void cardReturnsFieldsLanguagesAndSkills() throws Exception {
        // Навыки отсортированы по имени: "C#" раньше "Java".
        mockMvc.perform(get("/api/v1/postings/{id}", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("A"))
                .andExpect(jsonPath("$.city").value("Berlin"))
                .andExpect(jsonPath("$.seniority").value("SENIOR"))
                .andExpect(jsonPath("$.experienceYearsMin").value(5))
                .andExpect(jsonPath("$.languages.length()").value(1))
                .andExpect(jsonPath("$.languages[0].languageCode").value("en"))
                .andExpect(jsonPath("$.skills.length()").value(2))
                .andExpect(jsonPath("$.skills[0].skill").value("C#"))
                .andExpect(jsonPath("$.skills[0].stance").value("NEGATED"))
                .andExpect(jsonPath("$.skills[1].skill").value("Java"))
                .andExpect(jsonPath("$.skills[1].stance").value("REQUESTED"));
    }

    @Test
    void missingCardReturnsProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/postings/{id}", 9_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
