package com.roleorienta.api.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roleorienta.api.TestcontainersConfiguration;
import com.roleorienta.api.auth.AppUserService;
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
 * Интеграционный тест очереди подтверждения кандидатов (§33) на реальном PostgreSQL
 * (Testcontainers, схема — Flyway) через MockMvc с цепочкой безопасности. Проверяет
 * роль-защиту {@code /api/v1/admin/**}, постановку обнаружения в outbox, подтверждение
 * (заводится источник ACTIVE) и 404 на несуществующего кандидата.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EmployerDiscoveryAdminIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserService appUserService;

    private MockMvc mockMvc;

    private static final String ADMIN = "admin@example.com";
    private static final String USER = "user@example.com";
    private static final String PASSWORD = "password123";
    private static final String BASE = "/api/v1/admin/employer-candidates";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        jdbcTemplate.update("DELETE FROM employer_candidate");
        jdbcTemplate.update("DELETE FROM saved_posting");
        jdbcTemplate.update("DELETE FROM posting_skill");
        jdbcTemplate.update("DELETE FROM posting_language");
        jdbcTemplate.update("DELETE FROM posting_revision");
        jdbcTemplate.update("DELETE FROM posting_report");
        jdbcTemplate.update("DELETE FROM job_posting");
        jdbcTemplate.update("DELETE FROM company_source");
        jdbcTemplate.update("DELETE FROM source");
        jdbcTemplate.update("DELETE FROM company");
        jdbcTemplate.update("DELETE FROM provider");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM app_user");

        appUserService.createAdminIfAbsent(ADMIN, PASSWORD);
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

    private long insertPendingCandidate() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO employer_candidate (provider_code, slug, base_url, state, confidence, posting_count) "
                        + "VALUES ('greenhouse','acme','http://stub','PENDING','LOW',0) RETURNING id",
                Long.class);
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        MockHttpSession userSession = registerAndLoginUser();
        mockMvc.perform(get(BASE).session(userSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListsQueue() throws Exception {
        insertPendingCandidate();
        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(get(BASE).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].slug").value("acme"))
                .andExpect(jsonPath("$[0].state").value("PENDING"));
    }

    @Test
    void discoverEnqueuesOutboxEvent() throws Exception {
        MockHttpSession admin = login(ADMIN);
        String body = "{\"providerCode\":\"greenhouse\",\"slug\":\"beta\",\"baseUrl\":\"http://stub\"}";
        mockMvc.perform(post(BASE + "/discover").with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'DISCOVER_EMPLOYER'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void confirmCreatesActiveSource() throws Exception {
        long id = insertPendingCandidate();
        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(post(BASE + "/{id}/confirm", id).with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"companyName\":\"Acme Inc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.sourceId").isNotEmpty());

        Integer sources = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM source WHERE external_ref = 'acme' AND state = 'ACTIVE'", Integer.class);
        assertEquals(1, sources);
        Integer companies = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company WHERE name = 'Acme Inc'", Integer.class);
        assertEquals(1, companies);
    }

    @Test
    void confirmMissingCandidateIsNotFound() throws Exception {
        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(post(BASE + "/{id}/confirm", 999999).with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
