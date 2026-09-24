package com.roleorienta.api.application;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roleorienta.api.TestcontainersConfiguration;
import java.time.Instant;
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
 * Интеграционный тест собеседований (§43) на реальном PostgreSQL (Testcontainers,
 * схема — Flyway) через MockMvc с цепочкой безопасности. Проверяет назначение/список/
 * перенос/отмену, валидацию таймзоны и времени (400), 409 на перенос отменённого и
 * приватность на <b>втором</b> пользователе (A23).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InterviewApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long postingId;

    private static final String PASSWORD = "password123";
    private static final String ZONE = "Europe/Amsterdam";

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

    /** FK-безопасная очистка: interview/application_note — перед application и т.д. (§33.8). */
    private void cleanDatabase() {
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

    private long createApplication(MockHttpSession session) throws Exception {
        mockMvc.perform(post("/api/v1/applications").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postingId\":" + postingId + "}"))
                .andExpect(status().isCreated());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM application WHERE job_posting_id = ?", Long.class, postingId);
    }

    private String scheduleBody(Instant at, String zone) {
        return "{\"scheduledAt\":\"" + at.toString() + "\",\"zoneId\":\"" + zone + "\"}";
    }

    private long interviewIdFor(long applicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM interview WHERE application_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, applicationId);
    }

    @Test
    void scheduleListRescheduleAndCancel() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        long applicationId = createApplication(session);
        Instant at = Instant.now().plusSeconds(3600);

        mockMvc.perform(post("/api/v1/applications/{id}/interviews", applicationId)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(scheduleBody(at, ZONE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.zoneId").value(ZONE));

        long interviewId = interviewIdFor(applicationId);

        mockMvc.perform(get("/api/v1/applications/{id}/interviews", applicationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"));

        Instant later = Instant.now().plusSeconds(7200);
        mockMvc.perform(patch("/api/v1/applications/{id}/interviews/{iid}", applicationId, interviewId)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(later, "America/New_York")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneId").value("America/New_York"));

        mockMvc.perform(post("/api/v1/applications/{id}/interviews/{iid}/cancel",
                        applicationId, interviewId).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Перенос отменённого — конфликт.
        mockMvc.perform(patch("/api/v1/applications/{id}/interviews/{iid}", applicationId, interviewId)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(scheduleBody(later, ZONE)))
                .andExpect(status().isConflict());
    }

    @Test
    void schedulePastTimeIsBadRequest() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        long applicationId = createApplication(session);

        mockMvc.perform(post("/api/v1/applications/{id}/interviews", applicationId)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(Instant.now().minusSeconds(3600), ZONE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scheduleInvalidZoneIsBadRequest() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        long applicationId = createApplication(session);

        mockMvc.perform(post("/api/v1/applications/{id}/interviews", applicationId)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(Instant.now().plusSeconds(3600), "Mars/Olympus")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void interviewsArePrivateToOwner() throws Exception {
        MockHttpSession first = registerAndLogin("a@example.com");
        long applicationId = createApplication(first);
        mockMvc.perform(post("/api/v1/applications/{id}/interviews", applicationId)
                        .with(csrf()).session(first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(Instant.now().plusSeconds(3600), ZONE)))
                .andExpect(status().isCreated());

        MockHttpSession second = registerAndLogin("b@example.com");
        mockMvc.perform(get("/api/v1/applications/{id}/interviews", applicationId).session(second))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/applications/{id}/interviews", applicationId)
                        .with(csrf()).session(second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(Instant.now().plusSeconds(3600), ZONE)))
                .andExpect(status().isNotFound());
    }

    @Test
    void schedulingPromotesApplicationToInterviewing() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        long applicationId = createApplication(session);

        // Отклик стартует в APPLIED.
        mockMvc.perform(get("/api/v1/applications/{id}", applicationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("APPLIED"));

        mockMvc.perform(post("/api/v1/applications/{id}/interviews", applicationId)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(Instant.now().plusSeconds(3600), ZONE)))
                .andExpect(status().isCreated());

        // §45: назначение собеседования подняло отклик до INTERVIEWING.
        mockMvc.perform(get("/api/v1/applications/{id}", applicationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("INTERVIEWING"));
    }
}
