package com.roleorienta.api.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Интеграционный тест персональных маркеров публикаций (§7, §30) на реальном PostgreSQL
 * (Testcontainers, схема — Flyway) через MockMvc с применённой цепочкой безопасности
 * ({@code apply(springSecurity())}, как {@link com.roleorienta.api.auth.AuthApiIntegrationTest}).
 *
 * <p>Проверяет то, что не видит юнит-тест сервиса: маршруты и JSON, коды статусов, требование
 * CSRF и аутентификации, реальную запись в {@code saved_posting} и — главное — <b>проверку
 * владельца на втором пользователе (A23)</b>: пользователи не видят и не могут изменить маркеры
 * друг друга. Публикация засевается напрямую в базу (в API-приложении нет записи публикаций),
 * пользователи заводятся штатной регистрацией; аутентификация переносится повторным
 * использованием объекта сессии (как в тесте auth).</p>
 *
 * <p>Изоляция: перед каждым тестом очищаются маркеры, пользователи, сессии и посевные данные.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SavedPostingApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long postingId;

    private static final String USER_A = "a@example.com";
    private static final String USER_B = "b@example.com";
    private static final String PASSWORD = "password123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        jdbcTemplate.update("DELETE FROM saved_posting");
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update("DELETE FROM posting_skill");
        jdbcTemplate.update("DELETE FROM posting_language");
        jdbcTemplate.update("DELETE FROM posting_revision");
        jdbcTemplate.update("DELETE FROM posting_report");
        jdbcTemplate.update("DELETE FROM job_posting");
        jdbcTemplate.update("DELETE FROM source");
        jdbcTemplate.update("DELETE FROM provider");

        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (code, display_name, kind) "
                        + "VALUES ('greenhouse','Greenhouse','ATS') RETURNING id",
                Long.class);
        Long sourceId = jdbcTemplate.queryForObject(
                "INSERT INTO source (provider_id, kind, external_ref, base_url, state) "
                        + "VALUES (?, 'COMPANY_BOARD', 'acme', 'http://stub', 'ACTIVE') RETURNING id",
                Long.class, providerId);
        postingId = jdbcTemplate.queryForObject(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at) "
                        + "VALUES (?, 'A', 'http://stub/A', 'Posting A', now(), now()) RETURNING id",
                Long.class, sourceId);
    }

    private String credentials(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    /** Регистрирует пользователя и входит, возвращая объект сессии с аутентификацией. */
    private MockHttpSession registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    void saveThenListReturnsMarkerForOwner() throws Exception {
        MockHttpSession a = registerAndLogin(USER_A);

        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId).with(csrf()).session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postingId").value((int) postingId))
                .andExpect(jsonPath("$.state").value("SAVED"));

        mockMvc.perform(get("/api/v1/me/saved-postings").session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].postingId").value((int) postingId));
    }

    @Test
    void saveIsIdempotent() throws Exception {
        MockHttpSession a = registerAndLogin(USER_A);

        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId).with(csrf()).session(a))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId).with(csrf()).session(a))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me/saved-postings").session(a))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void hidePersistsReason() throws Exception {
        MockHttpSession a = registerAndLogin(USER_A);

        mockMvc.perform(post("/api/v1/postings/{id}/hide", postingId).with(csrf()).session(a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"дубликат\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("HIDDEN"))
                .andExpect(jsonPath("$.hiddenReason").value("дубликат"));

        // Скрытая публикация не попадает в список сохранённых.
        mockMvc.perform(get("/api/v1/me/saved-postings").session(a))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void hideAcceptsEmptyBody() throws Exception {
        MockHttpSession a = registerAndLogin(USER_A);

        mockMvc.perform(post("/api/v1/postings/{id}/hide", postingId).with(csrf()).session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("HIDDEN"))
                .andExpect(jsonPath("$.hiddenReason").isEmpty());
    }

    @Test
    void removeUnsetsMarker() throws Exception {
        MockHttpSession a = registerAndLogin(USER_A);
        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId).with(csrf()).session(a))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/postings/{id}/saved", postingId).with(csrf()).session(a))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/saved-postings").session(a))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** A23: маркеры разных пользователей независимы; чужой список и чужой маркер недоступны. */
    @Test
    void markersAreIsolatedPerOwner() throws Exception {
        MockHttpSession a = registerAndLogin(USER_A);
        MockHttpSession b = registerAndLogin(USER_B);

        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId).with(csrf()).session(a))
                .andExpect(status().isOk());

        // B не видит сохранённое A.
        mockMvc.perform(get("/api/v1/me/saved-postings").session(b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // B сохраняет ту же публикацию — своя независимая строка.
        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId).with(csrf()).session(b))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/saved-postings").session(b))
                .andExpect(jsonPath("$.length()").value(1));

        // Снятие маркера у B не затрагивает маркер A.
        mockMvc.perform(delete("/api/v1/postings/{id}/saved", postingId).with(csrf()).session(b))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me/saved-postings").session(a))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void saveOnMissingPostingReturnsProblemJson() throws Exception {
        MockHttpSession a = registerAndLogin(USER_A);

        mockMvc.perform(post("/api/v1/postings/{id}/save", 9_999_999L).with(csrf()).session(a))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void saveWithoutCsrfIsForbidden() throws Exception {
        MockHttpSession a = registerAndLogin(USER_A);

        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId).session(a))
                .andExpect(status().isForbidden());
    }

    @Test
    void saveWithoutAuthenticationIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void savedListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me/saved-postings"))
                .andExpect(status().isUnauthorized());
    }
}
