package com.roleorienta.api.report;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roleorienta.api.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Интеграционный тест жалоб на публикации (§46) на реальном PostgreSQL (Testcontainers,
 * схема — Flyway) через MockMvc с цепочкой безопасности. Проверяет создание/список,
 * идемпотентность, 404 на нет-публикацию, 401 без входа и приватность на <b>втором</b>
 * пользователе (A23).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostingReportApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long postingId;

    private static final String PASSWORD = "password123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        cleanDatabase();

        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (code, display_name, kind) "
                        + "VALUES ('greenhouse','Greenhouse','ATS') RETURNING id", Long.class);
        Long sourceId = jdbcTemplate.queryForObject(
                "INSERT INTO source (provider_id, kind, external_ref, base_url, state) "
                        + "VALUES (?, 'COMPANY_BOARD','acme','http://stub','ACTIVE') RETURNING id",
                Long.class, providerId);
        postingId = jdbcTemplate.queryForObject(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at) "
                        + "VALUES (?, 'P1','http://stub/P1','Posting 1', now(), now()) RETURNING id",
                Long.class, sourceId);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** FK-безопасная очистка: posting_report — перед job_posting и app_user (§33.8). */
    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM posting_report");
        jdbcTemplate.update("DELETE FROM interview");
        jdbcTemplate.update("DELETE FROM application_note");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM notification");
        jdbcTemplate.update("DELETE FROM company_subscription");
        jdbcTemplate.update("DELETE FROM saved_posting");
        jdbcTemplate.update("DELETE FROM posting_skill");
        jdbcTemplate.update("DELETE FROM posting_language");
        jdbcTemplate.update("DELETE FROM posting_revision");
        jdbcTemplate.update("DELETE FROM pending_change");
        jdbcTemplate.update("DELETE FROM company_source");
        jdbcTemplate.update("DELETE FROM employer_candidate");
        jdbcTemplate.update("DELETE FROM job_posting");
        jdbcTemplate.update("DELETE FROM source");
        jdbcTemplate.update("DELETE FROM company");
        jdbcTemplate.update("DELETE FROM provider");
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    private String creds(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private MockHttpSession registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private String reportBody(String reason, String comment) {
        return "{\"reason\":\"" + reason + "\",\"comment\":\"" + comment + "\"}";
    }

    @Test
    void reportAndListOwn() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");

        mockMvc.perform(post("/api/v1/postings/{id}/reports", postingId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("BROKEN_LINK", "ссылка ведёт на 404")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.complaint.reason").value("BROKEN_LINK"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.postingId").value(postingId));

        mockMvc.perform(get("/api/v1/reports").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].complaint.reason").value("BROKEN_LINK"));
    }

    @Test
    void repeatedReportIsIdempotent() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        mockMvc.perform(post("/api/v1/postings/{id}/reports", postingId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("DUPLICATE", "дубль"))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/postings/{id}/reports", postingId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("OUTDATED", "устарела"))).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/reports").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].complaint.reason").value("DUPLICATE"));
    }

    @Test
    void reportMissingPostingIsNotFound() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        mockMvc.perform(post("/api/v1/postings/{id}/reports", 999999L).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("OTHER", "нет такой")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedReportIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/postings/{id}/reports", postingId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("BROKEN_LINK", "аноним")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportsArePrivateToOwner() throws Exception {
        MockHttpSession first = registerAndLogin("a@example.com");
        mockMvc.perform(post("/api/v1/postings/{id}/reports", postingId).with(csrf()).session(first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("BROKEN_LINK", "моя жалоба")))
                .andExpect(status().isCreated());

        MockHttpSession second = registerAndLogin("b@example.com");
        mockMvc.perform(get("/api/v1/reports").session(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
