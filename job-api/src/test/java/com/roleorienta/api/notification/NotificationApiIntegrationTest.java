package com.roleorienta.api.notification;

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
 * Интеграционный тест чтения уведомлений (§40) на реальном PostgreSQL (Testcontainers,
 * схема — Flyway) через MockMvc с цепочкой безопасности. Проверяет, что пользователь
 * видит свои уведомления и не видит чужие (A23) на <b>втором</b> пользователе.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long companyId;
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
        companyId = jdbcTemplate.queryForObject(
                "INSERT INTO company (name) VALUES ('Acme Inc') RETURNING id", Long.class);
        postingId = jdbcTemplate.queryForObject(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at) "
                        + "VALUES (?, 'P1','http://stub/P1','Posting 1', now(), now()) RETURNING id",
                Long.class, sourceId);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** FK-безопасная очистка: дети job_posting/company/app_user — перед ними (урок §33.8). */
    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM notification");
        jdbcTemplate.update("DELETE FROM company_subscription");
        jdbcTemplate.update("DELETE FROM saved_posting");
        jdbcTemplate.update("DELETE FROM posting_skill");
        jdbcTemplate.update("DELETE FROM posting_language");
        jdbcTemplate.update("DELETE FROM posting_revision");
        jdbcTemplate.update("DELETE FROM pending_change");
        jdbcTemplate.update("DELETE FROM company_source");
        jdbcTemplate.update("DELETE FROM employer_candidate");
        jdbcTemplate.update("DELETE FROM posting_report");
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

    private long userId(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE lower(email) = lower(?)", Long.class, email);
    }

    private void insertNotification(long appUserId) {
        jdbcTemplate.update(
                "INSERT INTO notification (app_user_id, job_posting_id, company_id, field_name) "
                        + "VALUES (?, ?, ?, 'salary_min')",
                appUserId, postingId, companyId);
    }

    @Test
    void userSeesOwnNotifications() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        insertNotification(userId("a@example.com"));

        mockMvc.perform(get("/api/v1/notifications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].change.postingId").value(postingId))
                .andExpect(jsonPath("$[0].change.companyId").value(companyId))
                .andExpect(jsonPath("$[0].change.fieldName").value("salary_min"));
    }

    @Test
    void notificationsArePrivateToOwner() throws Exception {
        registerAndLogin("a@example.com");
        insertNotification(userId("a@example.com"));

        MockHttpSession second = registerAndLogin("b@example.com");
        mockMvc.perform(get("/api/v1/notifications").session(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
