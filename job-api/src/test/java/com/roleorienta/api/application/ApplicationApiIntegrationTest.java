package com.roleorienta.api.application;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roleorienta.api.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Интеграционный тест откликов и заметок (§41) и переходов статуса с оптимистичной
 * конкуренцией (§42, A19) на реальном PostgreSQL (Testcontainers, схема — Flyway) через
 * MockMvc с цепочкой безопасности. Проверяет создание/список/карточку с заметкой,
 * приватность на <b>втором</b> пользователе (A23) и цикл {@code If-Match}/{@code ETag}
 * (200 → 412 → 428 → 409).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationApiIntegrationTest {

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

    /** FK-безопасная очистка: application_note/application и прочие дети — перед родителями (§33.8). */
    private void cleanDatabase() {
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

    private long createApplication(MockHttpSession session) throws Exception {
        mockMvc.perform(post("/api/v1/applications").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postingId\":" + postingId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM application WHERE job_posting_id = ?", Long.class, postingId);
    }

    @Test
    void createListAndCardWithNote() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        long applicationId = createApplication(session);

        mockMvc.perform(get("/api/v1/applications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].postingId").value(postingId));

        mockMvc.perform(post("/api/v1/applications/{id}/notes", applicationId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"call recruiter\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("call recruiter"));

        mockMvc.perform(get("/api/v1/applications/{id}", applicationId).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.postingId").value(postingId))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.notes.length()").value(1))
                .andExpect(jsonPath("$.notes[0].body").value("call recruiter"));
    }

    @Test
    void repeatedApplyIsIdempotent() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        createApplication(session);
        createApplication(session);

        mockMvc.perform(get("/api/v1/applications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void applicationIsPrivateToOwner() throws Exception {
        MockHttpSession first = registerAndLogin("a@example.com");
        long applicationId = createApplication(first);

        MockHttpSession second = registerAndLogin("b@example.com");
        mockMvc.perform(get("/api/v1/applications").session(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/applications/{id}", applicationId).session(second))
                .andExpect(status().isNotFound());
    }

    /** A19: цикл смены статуса — корректный If-Match → 200, затем устаревший → 412 (§42). */
    @Test
    void statusChangeWithIfMatchThenStaleIsPreconditionFailed() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        long applicationId = createApplication(session);

        MvcResult card = mockMvc.perform(get("/api/v1/applications/{id}", applicationId).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();
        String etag = card.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(patch("/api/v1/applications/{id}", applicationId).with(csrf()).session(session)
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INTERVIEWING\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.status").value("INTERVIEWING"))
                .andExpect(jsonPath("$.version").value(1));

        // Повтор со старым ETag — устаревшее предусловие.
        mockMvc.perform(patch("/api/v1/applications/{id}", applicationId).with(csrf()).session(session)
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFER\"}"))
                .andExpect(status().isPreconditionFailed());
    }

    /** A19: без заголовка If-Match смена статуса запрещена → 428 (§42). */
    @Test
    void statusChangeWithoutIfMatchIsPreconditionRequired() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        long applicationId = createApplication(session);

        mockMvc.perform(patch("/api/v1/applications/{id}", applicationId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INTERVIEWING\"}"))
                .andExpect(status().isPreconditionRequired());
    }

    /** §7.8: недопустимый переход (APPLIED → OFFER) при корректном If-Match → 409 (§42). */
    @Test
    void disallowedTransitionIsConflict() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        long applicationId = createApplication(session);

        mockMvc.perform(patch("/api/v1/applications/{id}", applicationId).with(csrf()).session(session)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFER\"}"))
                .andExpect(status().isConflict());
    }
}
