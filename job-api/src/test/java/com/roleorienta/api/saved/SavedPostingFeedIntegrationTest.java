package com.roleorienta.api.saved;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Интеграционный тест персонализации ленты (§31) на реальном PostgreSQL (Testcontainers,
 * схема — Flyway) через MockMvc с цепочкой безопасности. Проверяет, что для вошедшего
 * пользователя скрытые им публикации исключаются из ленты (и возвращаются по
 * {@code includeHidden=true}), а элементы помечаются его отношением (saved/hidden/seen);
 * для анонимного запроса лента не персонализирована.
 *
 * <p>Две публикации засеваются напрямую в базу; пользователь заводится штатной
 * регистрацией, аутентификация переносится объектом сессии (как в тесте auth).</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SavedPostingFeedIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long postingId1;
    private long postingId2;

    private static final String USER = "feed@example.com";
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
        postingId1 = jdbcTemplate.queryForObject(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at) "
                        + "VALUES (?, 'P1', 'http://stub/P1', 'Posting 1', now(), now()) RETURNING id",
                Long.class, sourceId);
        postingId2 = jdbcTemplate.queryForObject(
                "INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at) "
                        + "VALUES (?, 'P2', 'http://stub/P2', 'Posting 2', now(), now()) RETURNING id",
                Long.class, sourceId);
    }

    private String credentials() {
        return "{\"email\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private MockHttpSession registerAndLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials()))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials()))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    void anonymousFeedShowsAllPostingsWithoutViewerMarker() throws Exception {
        mockMvc.perform(get("/api/v1/postings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].viewerState").isEmpty())
                .andExpect(jsonPath("$.items[0].viewerSeen").value(false));
    }

    @Test
    void hiddenPostingIsExcludedForOwner() throws Exception {
        MockHttpSession session = registerAndLogin();
        mockMvc.perform(post("/api/v1/postings/{id}/hide", postingId1).with(csrf()).session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/postings").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].externalId").value("P2"));
    }

    @Test
    void includeHiddenReturnsHiddenAnnotated() throws Exception {
        MockHttpSession session = registerAndLogin();
        mockMvc.perform(post("/api/v1/postings/{id}/hide", postingId1).with(csrf()).session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/postings").param("includeHidden", "true").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].externalId").value("P1"))
                .andExpect(jsonPath("$.items[0].viewerState").value("HIDDEN"));
    }

    @Test
    void savedAndSeenAreAnnotatedInFeed() throws Exception {
        MockHttpSession session = registerAndLogin();
        mockMvc.perform(post("/api/v1/postings/{id}/save", postingId2).with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/postings/{id}/seen", postingId2).with(csrf()).session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/postings").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].externalId").value("P1"))
                .andExpect(jsonPath("$.items[0].viewerState").isEmpty())
                .andExpect(jsonPath("$.items[1].externalId").value("P2"))
                .andExpect(jsonPath("$.items[1].viewerState").value("SAVED"))
                .andExpect(jsonPath("$.items[1].viewerSeen").value(true));
    }
}
