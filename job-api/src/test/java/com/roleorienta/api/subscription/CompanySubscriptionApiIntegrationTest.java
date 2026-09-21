package com.roleorienta.api.subscription;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Интеграционный тест подписок на компании (§37) на реальном PostgreSQL (Testcontainers,
 * схема — Flyway) через MockMvc с цепочкой безопасности. Проверяет подписку/отписку,
 * список, приватность на <b>втором</b> пользователе (A23) и 404 на несуществующую компанию.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CompanySubscriptionApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long companyId;

    private static final String PASSWORD = "password123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        jdbcTemplate.update("DELETE FROM company_subscription");
        jdbcTemplate.update("DELETE FROM company_source");
        jdbcTemplate.update("DELETE FROM employer_candidate");
        jdbcTemplate.update("DELETE FROM company");
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM app_user");

        companyId = jdbcTemplate.queryForObject(
                "INSERT INTO company (name) VALUES ('Acme Inc') RETURNING id", Long.class);
    }

    /**
     * Убирает за собой: строки company_subscription/company создаёт только этот тест,
     * поэтому чистим их после каждого метода, чтобы очистка соседних тест-классов
     * (удаляющих app_user/company) не наткнулась на внешний ключ (урок §33.8).
     */
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM company_subscription");
        jdbcTemplate.update("DELETE FROM company");
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

    @Test
    void subscribeThenListShowsCompany() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");

        mockMvc.perform(put("/api/v1/companies/{id}/subscription", companyId).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyId))
                .andExpect(jsonPath("$.companyName").value("Acme Inc"));

        mockMvc.perform(get("/api/v1/me/company-subscriptions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].companyId").value(companyId));
    }

    @Test
    void subscriptionIsPrivateToOwner() throws Exception {
        MockHttpSession first = registerAndLogin("a@example.com");
        mockMvc.perform(put("/api/v1/companies/{id}/subscription", companyId).with(csrf()).session(first))
                .andExpect(status().isOk());

        MockHttpSession second = registerAndLogin("b@example.com");
        mockMvc.perform(get("/api/v1/me/company-subscriptions").session(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unsubscribeRemovesSubscription() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        mockMvc.perform(put("/api/v1/companies/{id}/subscription", companyId).with(csrf()).session(session))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/companies/{id}/subscription", companyId).with(csrf()).session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/company-subscriptions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void subscribeToMissingCompanyIsNotFound() throws Exception {
        MockHttpSession session = registerAndLogin("a@example.com");
        mockMvc.perform(put("/api/v1/companies/{id}/subscription", 999999).with(csrf()).session(session))
                .andExpect(status().isNotFound());
    }
}
