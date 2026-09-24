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
                        + "city, country, work_modality, salary_max, salary_currency, seniority, experience_years_min, "
                        + "posted_on, additional_locations) "
                        + "VALUES (?, 'A', 'http://stub/A', 'Posting A', now(), now(), "
                        + "'Berlin', 'Germany', 'UNKNOWN', 110000, 'EUR', 'SENIOR', 5, "
                        + "DATE '2026-09-10', 'Vienna, Austria; Bratislava, Slovakia') RETURNING id",
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
    void coverageUnknownByDefaultAndFilterBySiteOnly() throws Exception {
        // A5, §81: без оценки — «не проверено»; coverage=SITE_ONLY — только «скрытые».
        jdbcTemplate.update("INSERT INTO coverage_assessment (job_posting_id, state, checked_platforms) "
                + "VALUES (?, 'SITE_ONLY', 'profesia.sk')", cardId);
        mockMvc.perform(get("/api/v1/postings").param("coverage", "SITE_ONLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].coverage.state").value("SITE_ONLY"))
                .andExpect(jsonPath("$.items[0].coverage.checkedPlatforms").value("profesia.sk"));
        mockMvc.perform(get("/api/v1/postings").param("coverage", "UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].coverage.state").value("UNKNOWN"));
        mockMvc.perform(get("/api/v1/postings/{id}", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.state").value("SITE_ONLY"));
    }

    @Test
    void feedReturnsAllPostingsWithoutCursorWhenFewer() throws Exception {
        mockMvc.perform(get("/api/v1/postings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].head.externalId").value("A"))
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
                .andExpect(jsonPath("$.items[0].head.externalId").value("C"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void filterByWorkModality() throws Exception {
        mockMvc.perform(get("/api/v1/postings").param("workModality", "REMOTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].head.externalId").value("B"));
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
                .andExpect(jsonPath("$.items[0].head.externalId").value("A"));
    }

    @Test
    void filterByMinSalaryUsesUpperBound() throws Exception {
        // A(110000) и C(115000) проходят; B(95000) отсекается.
        mockMvc.perform(get("/api/v1/postings").param("minSalary", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void filterByCountryMatchesAdditionalLocations() throws Exception {
        // У A основная страна Germany, доп. локации «Vienna, Austria; Bratislava, Slovakia» (§68).
        mockMvc.perform(get("/api/v1/postings").param("country", "slovakia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].head.externalId").value("A"));
        // Страна — последний сегмент записи, не подстрока: «Vienna» — город, не страна.
        mockMvc.perform(get("/api/v1/postings").param("country", "Vienna"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void filterByMinSalaryTakesLowerBoundWhenUpperIsUnknownAndRowShowsPeriod() throws Exception {
        // D «от 120000 в год» (только нижняя граница, §66) проходит порог 100000; E «от 90000» — нет.
        Long sourceId = jdbcTemplate.queryForObject("SELECT id FROM source LIMIT 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at, "
                        + "salary_min, salary_currency, salary_period, salary_basis) VALUES "
                        + "(?, 'D', 'http://stub/D', 'Posting D', now(), now(), 120000, 'EUR', 'YEAR', 'GROSS'), "
                        + "(?, 'E', 'http://stub/E', 'Posting E', now(), now(), 90000, 'EUR', 'YEAR', 'GROSS')",
                sourceId, sourceId);
        mockMvc.perform(get("/api/v1/postings").param("minSalary", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[2].head.externalId").value("D"))
                .andExpect(jsonPath("$.items[2].facts.salary.period").value("YEAR"))
                .andExpect(jsonPath("$.items[2].facts.salary.basis").value("GROSS"));
    }

    @Test
    void filterByPostedFrom() throws Exception {
        // Дата публикации есть только у A (2026-09-10); публикации без даты под фильтр не попадают.
        mockMvc.perform(get("/api/v1/postings").param("postedFrom", "2026-09-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].head.externalId").value("A"));
        mockMvc.perform(get("/api/v1/postings").param("postedFrom", "2026-09-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void sortPostedNewestFirstUndatedLastAndPaginates() throws Exception {
        // §70: дата есть только у A — она первой; B и C без даты — в конце, по убыванию id.
        String firstPage = mockMvc.perform(get("/api/v1/postings").param("sort", "POSTED").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].head.externalId").value("A"))
                .andExpect(jsonPath("$.items[1].head.externalId").value("C"))
                .andExpect(jsonPath("$.nextCursor").isNumber())
                .andReturn().getResponse().getContentAsString();
        String cursor = firstPage.replaceAll(".*\"nextCursor\":(\\d+).*", "$1");
        mockMvc.perform(get("/api/v1/postings").param("sort", "POSTED").param("limit", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].head.externalId").value("B"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void cardReturnsFieldsLanguagesAndSkills() throws Exception {
        // Навыки отсортированы по имени: "C#" раньше "Java".
        mockMvc.perform(get("/api/v1/postings/{id}", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.externalId").value("A"))
                .andExpect(jsonPath("$.facts.location.city").value("Berlin"))
                .andExpect(jsonPath("$.facts.experience.seniority").value("SENIOR"))
                .andExpect(jsonPath("$.facts.experience.yearsMin").value(5))
                .andExpect(jsonPath("$.facts.timeline.postedOn").value("2026-09-10"))
                .andExpect(jsonPath("$.facts.location.additional").value("Vienna, Austria; Bratislava, Slovakia"))
                .andExpect(jsonPath("$.requirements.languages.length()").value(1))
                .andExpect(jsonPath("$.requirements.languages[0].languageCode").value("en"))
                .andExpect(jsonPath("$.requirements.skills.length()").value(2))
                .andExpect(jsonPath("$.requirements.skills[0].skill").value("C#"))
                .andExpect(jsonPath("$.requirements.skills[0].stance").value("NEGATED"))
                .andExpect(jsonPath("$.requirements.skills[1].skill").value("Java"))
                .andExpect(jsonPath("$.requirements.skills[1].stance").value("REQUESTED"));
    }

    @Test
    void missingCardReturnsProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/postings/{id}", 9_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
