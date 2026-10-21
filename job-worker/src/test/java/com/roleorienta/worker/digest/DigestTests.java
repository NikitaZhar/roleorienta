package com.roleorienta.worker.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.digest.DigestRepository.Due;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Сводка новых вакансий на почту (бизнес-описание §6; стенограмма §164) с настоящей отправкой SMTP в Mailpit —
 * почтовый сервер для тестов в контейнере: принимает письма и отдаёт их по HTTP API
 * (https://mailpit.axllent.org/docs/api-v1/). Проверяется письмо, как его получит пользователь: тема на его языке,
 * строки вакансий; «Не интересно» в письмо не попадает; второй раз за день письма нет; нет новых — письма нет;
 * следующее письмо — только с вакансиями, выданными после прошлого. Кому пора — по часам пользователя и частоте.
 */
@SpringBootTest(properties = "app.digest.from=noreply@roleorienta.test")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DigestTests {

    private static final int SMTP_PORT = 1025;
    private static final int API_PORT = 8025;

    /** Mailpit: SMTP на 1025, HTTP API на 8025; готовность — {@code /livez}. Запускается один раз на класс. */
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.31.3"))
            .withExposedPorts(SMTP_PORT, API_PORT)
            .waitingFor(Wait.forHttp("/livez").forPort(API_PORT));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Суббота и понедельник — для частоты «по будням» и «раз в неделю». */
    private static final Instant SATURDAY_9_UTC = Instant.parse("2026-10-24T09:00:00Z");
    private static final Instant MONDAY_8_30_UTC = Instant.parse("2026-10-26T08:30:00Z");
    private static final LocalTime EIGHT = LocalTime.of(8, 0);

    static {
        MAILPIT.start();
    }

    @Autowired
    private DigestService digests;

    @Autowired
    private DigestRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long positionId;
    private long passRunId;

    /**
     * Почта — Mailpit без входа и шифрования (тестовый сервер).
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(SMTP_PORT));
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
    }

    /**
     * Чистые таблицы и почтовый ящик; позиция и проход выдачи.
     *
     * @throws Exception ошибка HTTP
     */
    @BeforeEach
    void setUp() throws Exception {
        for (String table : new String[] {"delivered_vacancy", "pass_run", "vacancy_status_history",
            "vacancy_user_status", "search_condition", "user_account", "vacancy_language", "vacancy_position_match",
            "position_term", "position", "job_posting", "vacancy"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        HTTP.send(HttpRequest.newBuilder(api("/api/v1/messages")).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        positionId = jdbcTemplate.queryForObject(
                "INSERT INTO position (code, name) VALUES ('java-developer', 'Java developer') RETURNING id", Long.class);
        passRunId = jdbcTemplate.queryForObject("INSERT INTO pass_run (window_start) VALUES (now()) RETURNING id",
                Long.class);
    }

    /**
     * Письмо на языке пользователя только с новыми вакансиями из результатов; повтор за день — без письма; день без
     * новых — без письма; следующее письмо — только с выданными после прошлого.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void sendsOnlyNewVacanciesOncePerDay() throws Exception {
        long user = user("ru@example.com", "ru", "DAILY");
        long condition = condition(user);
        delivered(condition, "Java Developer Alpha");
        long hidden = delivered(condition, "Java Developer Hidden");
        jdbcTemplate.update("INSERT INTO vacancy_user_status (user_id, vacancy_id, status, changed_at) "
                + "VALUES (?, ?, 'NOT_INTERESTED', now())", user, hidden);
        LocalDate day = LocalDate.of(2026, 10, 21);

        assertThat(digests.send(user, day)).isTrue();
        assertThat(digests.send(user, day)).isFalse();

        List<JsonNode> messages = messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).path("Subject").asText()).isEqualTo("roleorienta: новых вакансий — 1");
        assertThat(messages.get(0).path("To").get(0).path("Address").asText()).isEqualTo("ru@example.com");
        String text = text(messages.get(0));
        assertThat(text).contains("Java Developer Alpha", "Работодатель не указан", "SK", "Открыть roleorienta")
                .doesNotContain("Hidden");

        assertThat(digests.send(user, day.plusDays(1))).isFalse();
        assertThat(messages()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT digest_day FROM user_account WHERE id = ?", LocalDate.class,
                user)).isEqualTo(day.plusDays(1));

        delivered(condition, "Java Developer Beta");
        assertThat(digests.send(user, day.plusDays(2))).isTrue();
        List<JsonNode> later = messages();
        assertThat(later).hasSize(2);
        JsonNode newest = later.stream().filter(message -> !message.path("ID").asText()
                .equals(messages.get(0).path("ID").asText())).findFirst().orElseThrow();
        assertThat(text(newest)).contains("Java Developer Beta").doesNotContain("Alpha");
    }

    /**
     * Кому пора (§164): в субботу в 9:00 UTC — «каждый день», но не «по будням», «никогда» и уже отработавший сегодня;
     * в 7:00 — только тот, у кого по его часам уже 9:00 (Братислава); в понедельник в 8:30 — и «раз в неделю», и
     * отработавший в субботу.
     */
    @Test
    void dueFollowsLocalTimeAndFrequency() {
        long daily = user("daily@example.com", "en", "DAILY");
        user("weekdays@example.com", "en", "WEEKDAYS");
        long weekly = user("weekly@example.com", "en", "WEEKLY");
        user("never@example.com", "en", "NEVER");
        long done = user("done@example.com", "en", "DAILY");
        jdbcTemplate.update("UPDATE user_account SET digest_day = DATE '2026-10-24' WHERE id = ?", done);
        long bratislava = user("bratislava@example.com", "en", "DAILY");
        jdbcTemplate.update("UPDATE user_account SET time_zone = 'Europe/Bratislava' WHERE id = ?", bratislava);

        assertThat(repository.due(SATURDAY_9_UTC, EIGHT)).extracting(Due::userId).containsExactly(daily, bratislava);
        assertThat(repository.due(SATURDAY_9_UTC.minusSeconds(2 * 3600), EIGHT)).extracting(Due::userId)
                .containsExactly(bratislava);
        assertThat(repository.due(MONDAY_8_30_UTC, EIGHT)).extracting(Due::userId).contains(weekly, done);
        assertThat(repository.due(SATURDAY_9_UTC, EIGHT)).extracting(Due::day)
                .containsOnly(LocalDate.of(2026, 10, 24));
    }

    private long user(String email, String language, String frequency) {
        return jdbcTemplate.queryForObject("INSERT INTO user_account (email, password_hash, language, "
                + "digest_frequency) VALUES (?, '-', ?, ?) RETURNING id", Long.class, email, language, frequency);
    }

    private long condition(long user) {
        long condition = jdbcTemplate.queryForObject("INSERT INTO search_condition (user_id, countries, portion_limit, "
                + "active) VALUES (?, '{SK}', 20, TRUE) RETURNING id", Long.class, user);
        jdbcTemplate.update("INSERT INTO search_condition_position (search_condition_id, position_id) VALUES (?, ?)",
                condition, positionId);
        return condition;
    }

    /** Вакансия (Словакия, работодатель не указан), выданная по условиям сейчас. */
    private long delivered(long condition, String title) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, work_countries)
                VALUES ('ACTIVE', ?, 'https://example.com', now(), now(), '{SK}') RETURNING id
                """, Long.class, title);
        jdbcTemplate.update("INSERT INTO delivered_vacancy (search_condition_id, vacancy_id, pass_run_id, delivered_at) "
                + "VALUES (?, ?, ?, clock_timestamp())", condition, vacancy, passRunId);
        return vacancy;
    }

    /** Письма в Mailpit, новые первыми. */
    private static List<JsonNode> messages() throws IOException, InterruptedException {
        JsonNode list = JSON.readTree(get("/api/v1/messages"));
        List<JsonNode> messages = new ArrayList<>();
        list.path("messages").forEach(messages::add);
        return messages;
    }

    /** Текстовая часть письма. */
    private static String text(JsonNode message) throws IOException, InterruptedException {
        return JSON.readTree(get("/api/v1/message/" + message.path("ID").asText())).path("Text").asText();
    }

    private static String get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(api(path)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static URI api(String path) {
        return URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(API_PORT) + path);
    }
}
