package com.roleorienta.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roleorienta.api.TestcontainersConfiguration;
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
 * Интеграционный тест модуля auth (§9) на реальном PostgreSQL (Testcontainers, схема —
 * Flyway) через MockMvc с применённой цепочкой безопасности.
 *
 * <p>MockMvc собирается вручную из контекста с {@code apply(springSecurity())} — так же,
 * как {@code PostingApiIntegrationTest} избегает {@code @AutoConfigureMockMvc} (в Spring
 * Boot 4 её модуль на тестовом classpath не гарантирован). Постпроцессор {@code csrf()}
 * из spring-security-test подставляет валидный CSRF-токен в изменяющие запросы.</p>
 *
 * <p>Проверяет то, что не видит юнит-тест: маршруты и JSON, коды статусов, требование
 * CSRF, перенос аутентификации между запросами через сессию, выход и что открытая лента
 * остаётся доступна без входа. Аутентификация в тесте переносится повторным
 * использованием объекта сессии; хранилище сессий (Spring Session JDBC) — ортогональная
 * инфраструктура, её схему накатывает Flyway (V15).</p>
 *
 * <p>Изоляция: перед каждым тестом очищаются таблицы пользователей и сессий.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "password123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // saved_posting ссылается на app_user (V16) — очищаем до пользователей.
        jdbcTemplate.update("DELETE FROM saved_posting");
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    private String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private void register(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(email, password)))
                .andExpect(status().isCreated());
    }

    @Test
    void registerCreatesUserWithRoleUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(EMAIL, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void duplicateRegistrationReturnsConflictProblemJson() throws Exception {
        register(EMAIL, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(EMAIL, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void registerRejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("not-an-email", PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body(EMAIL, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        register(EMAIL, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(EMAIL, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginEstablishesSessionForMe() throws Exception {
        register(EMAIL, PASSWORD);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).as("сессия создана при входе").isNotNull();

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));

        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()).session(session))
                .andExpect(status().isNoContent());
    }

    @Test
    void meWithoutSessionReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicPostingsFeedRemainsAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/postings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }
}
