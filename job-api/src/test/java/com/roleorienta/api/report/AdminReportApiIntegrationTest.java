package com.roleorienta.api.report;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roleorienta.api.TestcontainersConfiguration;
import com.roleorienta.api.auth.AppUserService;
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
 * Интеграционный тест админ-разбора жалоб (§47) на реальном PostgreSQL (Testcontainers,
 * схема — Flyway) через MockMvc с цепочкой безопасности. Проверяет роль-защиту
 * {@code /api/v1/admin/**}, очередь с фильтром по статусу, перевод в терминальный статус,
 * 404 на нет-жалобу, 400 на возврат в OPEN и 409 на повторный разбор.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminReportApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserService appUserService;

    private MockMvc mockMvc;
    private long postingId;

    private static final String ADMIN = "admin@example.com";
    private static final String USER = "user@example.com";
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

        appUserService.createAdminIfAbsent(ADMIN, PASSWORD);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** FK-безопасная, порядок-независимая очистка: все дети — перед родителями (§33.8). */
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

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession registerAndLoginUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(creds(USER)))
                .andExpect(status().isCreated());
        return login(USER);
    }

    private long reportAs(MockHttpSession session) throws Exception {
        mockMvc.perform(post("/api/v1/postings/{id}/reports", postingId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"BROKEN_LINK\",\"comment\":\"404\"}"))
                .andExpect(status().isCreated());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM posting_report ORDER BY id DESC LIMIT 1", Long.class);
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        MockHttpSession user = registerAndLoginUser();
        mockMvc.perform(get("/api/v1/admin/reports").session(user))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListsQueueAndTriages() throws Exception {
        MockHttpSession user = registerAndLoginUser();
        long reportId = reportAs(user);
        MockHttpSession admin = login(ADMIN);

        mockMvc.perform(get("/api/v1/admin/reports").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].reporterEmail").value(USER));

        mockMvc.perform(get("/api/v1/admin/reports").param("status", "RESOLVED").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(patch("/api/v1/admin/reports/{id}", reportId).with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mockMvc.perform(get("/api/v1/admin/reports").param("status", "OPEN").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void triageMissingIsNotFound() throws Exception {
        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", 999999L).with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void triageBackToOpenIsBadRequest() throws Exception {
        MockHttpSession user = registerAndLoginUser();
        long reportId = reportAs(user);
        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", reportId).with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void triageAlreadyResolvedIsConflict() throws Exception {
        MockHttpSession user = registerAndLoginUser();
        long reportId = reportAs(user);
        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", reportId).with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", reportId).with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isConflict());
    }
}
