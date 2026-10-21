package com.roleorienta.worker.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.delivery.DeliveryProperties;
import com.roleorienta.worker.digest.DigestRepository.Due;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Сводка новых вакансий на почту (бизнес-описание §6; стенограмма §164, аудит §165) с настоящей отправкой SMTP в
 * Mailpit — почтовый сервер для тестов в контейнере: принимает письма и отдаёт их по HTTP API
 * (https://mailpit.axllent.org/docs/api-v1/). Проверяется письмо, как его получит пользователь: тема на его языке,
 * строки вакансий; в письме — только то, что видно в результатах; второй раз за день письма нет; нет новых — письма
 * нет; вакансия приходит в письме один раз, и после смены условий; «не присылать» после постановки задания — письма
 * нет; исходы задания при отказе почты. Кому пора — по часам пользователя и частоте.
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
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
    /** Дольше срока без подтверждения ({@code app.delivery.hide-after}, 30 дней). */
    private static final int UNCONFIRMED_DAYS = 40;

    static {
        MAILPIT.start();
    }

    @Autowired
    private DigestService digests;

    @Autowired
    private DigestRepository repository;

    @Autowired
    private DeliveryProperties delivery;

    @Autowired
    private ObjectProvider<JavaMailSender> mailSender;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
            "vacancy_user_status", "search_condition_position", "search_condition", "digest_vacancy",
            "user_account", "vacancy_language", "vacancy_position_match", "position_term", "position", "job_posting",
            "vacancy"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        HTTP.send(HttpRequest.newBuilder(api("/api/v1/messages")).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        positionId = jdbcTemplate.queryForObject(
                "INSERT INTO position (code, name) VALUES ('java-developer', 'Java developer') RETURNING id", Long.class);
        passRunId = jdbcTemplate.queryForObject("INSERT INTO pass_run (window_start) VALUES (now()) RETURNING id",
                Long.class);
    }

    /**
     * Письмо на языке пользователя только с новыми вакансиями; страны — названиями; повтор за день — без письма; день
     * без новых — без письма; следующее письмо — только с выданными после прошлого.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void sendsOnlyNewVacanciesOncePerDay() throws Exception {
        long user = user("ru@example.com", "ru", "DAILY");
        long condition = condition(user, "{SK}");
        deliver(condition, vacancy("Java Developer Alpha", "ACTIVE", 0));

        assertThat(digests.send(user, DAY)).isTrue();
        assertThat(digests.send(user, DAY)).isFalse();

        List<JsonNode> messages = messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).path("Subject").asText()).isEqualTo("roleorienta: новых вакансий — 1");
        assertThat(messages.get(0).path("To").get(0).path("Address").asText()).isEqualTo("ru@example.com");
        assertThat(text(messages.get(0))).contains("Java Developer Alpha", "Работодатель не указан", "Словакия",
                "Открыть roleorienta");

        assertThat(digests.send(user, DAY.plusDays(1))).isFalse();
        assertThat(messages()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT digest_day FROM user_account WHERE id = ?", LocalDate.class,
                user)).isEqualTo(DAY.plusDays(1));

        deliver(condition, vacancy("Java Developer Beta", "ACTIVE", 0));
        assertThat(digests.send(user, DAY.plusDays(2))).isTrue();
        List<JsonNode> later = messages();
        assertThat(later).hasSize(2);
        JsonNode newest = later.stream().filter(message -> !message.path("ID").asText()
                .equals(messages.get(0).path("ID").asText())).findFirst().orElseThrow();
        assertThat(text(newest)).contains("Java Developer Beta").doesNotContain("Alpha");
    }

    /**
     * В письме — только то, что видно в результатах (аудит §165): новая и просмотренная — да; «Не интересно», отклик,
     * закрытая, не подтверждённая дольше срока, без позиции условий, с другой страной — нет. Тема EN — в
     * единственном числе для одной вакансии и во множественном для нескольких.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void mailHasOnlyVacanciesShownInResults() throws Exception {
        long user = user("en@example.com", "en", "DAILY");
        long condition = condition(user, "{SK}");
        deliver(condition, vacancy("Shown New", "ACTIVE", 0));
        status(user, deliver(condition, vacancy("Shown Viewed", "ACTIVE", 0)), "VIEWED");
        status(user, deliver(condition, vacancy("Hidden Not interested", "ACTIVE", 0)), "NOT_INTERESTED");
        status(user, deliver(condition, vacancy("Hidden Applied", "ACTIVE", 0)), "APPLIED");
        deliver(condition, vacancy("Hidden Closed", "CLOSED", 0));
        deliver(condition, vacancy("Hidden Unconfirmed", "ACTIVE", UNCONFIRMED_DAYS));
        long unmatched = vacancy("Hidden Unmatched", "ACTIVE", 0);
        jdbcTemplate.update("DELETE FROM vacancy_position_match WHERE vacancy_id = ?", unmatched);
        deliver(condition, unmatched);
        long czech = vacancy("Hidden Czech", "ACTIVE", 0);
        jdbcTemplate.update("UPDATE vacancy SET work_countries = '{CZ}' WHERE id = ?", czech);
        deliver(condition, czech);

        assertThat(digests.send(user, DAY)).isTrue();

        JsonNode message = messages().get(0);
        assertThat(message.path("Subject").asText()).isEqualTo("roleorienta: 2 new vacancies");
        assertThat(text(message)).contains("Shown New", "Shown Viewed", "Slovakia").doesNotContain("Hidden");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM digest_vacancy WHERE user_id = ?", Integer.class,
                user)).isEqualTo(2);
    }

    /**
     * Вакансия приходит в письме один раз (аудит §165): новая версия условий выдаёт её заново — второго письма о ней
     * нет; одна новая — тема EN в единственном числе.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void vacancyIsMailedOnceAcrossConditionVersions() throws Exception {
        long user = user("once@example.com", "en", "DAILY");
        long first = condition(user, "{SK}");
        long vacancy = vacancy("Java Developer Once", "ACTIVE", 0);
        deliver(first, vacancy);
        assertThat(digests.send(user, DAY)).isTrue();
        assertThat(messages().get(0).path("Subject").asText()).isEqualTo("roleorienta: 1 new vacancy");

        jdbcTemplate.update("UPDATE search_condition SET active = FALSE WHERE id = ?", first);
        deliver(condition(user, "{SK,CZ}"), vacancy);

        assertThat(digests.send(user, DAY.plusDays(1))).isFalse();
        assertThat(messages()).hasSize(1);
    }

    /**
     * «Не присылать» выбрано после постановки задания (повтор после отказа почты): письма нет, день не отмечается
     * (аудит §165).
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void neverChosenAfterQueuingSendsNothing() throws Exception {
        long user = user("never@example.com", "en", "NEVER");
        deliver(condition(user, "{SK}"), vacancy("Java Developer", "ACTIVE", 0));

        assertThat(digests.send(user, DAY)).isFalse();

        assertThat(messages()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT digest_day FROM user_account WHERE id = ?", LocalDate.class,
                user)).isNull();
    }

    /**
     * Исходы задания {@code SEND_DIGEST} (аудит §165): почта не настроена (нет отправителя или сервера) — «не
     * выполнено»; почтовый сервер не отвечает — повтор позже; в обоих случаях отметки откатываются, и следующий
     * удачный раз письмо приходит со всеми вакансиями. Строк больше предела — «и ещё N».
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void taskOutcomesWhenMailFails() throws Exception {
        long user = user("outcome@example.com", "en", "DAILY");
        long condition = condition(user, "{SK}");
        deliver(condition, vacancy("Java Developer One", "ACTIVE", 0));
        deliver(condition, vacancy("Java Developer Two", "ACTIVE", 0));
        TaskRecord task = new TaskRecord(1, SendDigestHandler.TYPE, SendDigestHandler.payload(user, DAY), 0);

        assertThat(handler(mailSender, "", "localhost").handle(task)).isInstanceOf(TaskOutcome.Failed.class);
        assertThat(handler(mailSender, "noreply@roleorienta.test", "").handle(task))
                .isInstanceOf(TaskOutcome.Failed.class);
        JavaMailSenderImpl unreachable = new JavaMailSenderImpl();
        unreachable.setHost("localhost");
        unreachable.setPort(1);
        assertThat(handler(new StaticListableBeanFactory(Map.of("mailSender", unreachable))
                .getBeanProvider(JavaMailSender.class), "noreply@roleorienta.test", "localhost").handle(task))
                .isInstanceOf(TaskOutcome.Retry.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM digest_vacancy", Integer.class)).isZero();

        assertThat(handler(mailSender, "noreply@roleorienta.test", "localhost").handle(task))
                .isInstanceOf(TaskOutcome.Done.class);
        assertThat(text(messages().get(0))).contains("New vacancies matching your search: 2.",
                "…and 1 more in the app.");
    }

    /**
     * Кому пора (§164): в субботу в 9:00 UTC — «каждый день», но не «по будням», «никогда» и уже отработавший сегодня;
     * в 7:00 — только тот, у кого по его часам уже 9:00 (Братислава); в понедельник в 8:30 — и «по будням», и «раз в
     * неделю», и отработавший в субботу.
     */
    @Test
    void dueFollowsLocalTimeAndFrequency() {
        long daily = user("daily@example.com", "en", "DAILY");
        long weekdays = user("weekdays@example.com", "en", "WEEKDAYS");
        long weekly = user("weekly@example.com", "en", "WEEKLY");
        user("never@example.com", "en", "NEVER");
        long done = user("done@example.com", "en", "DAILY");
        jdbcTemplate.update("UPDATE user_account SET digest_day = DATE '2026-10-24' WHERE id = ?", done);
        long bratislava = user("bratislava@example.com", "en", "DAILY");
        jdbcTemplate.update("UPDATE user_account SET time_zone = 'Europe/Bratislava' WHERE id = ?", bratislava);

        assertThat(repository.due(SATURDAY_9_UTC, EIGHT)).extracting(Due::userId).containsExactly(daily, bratislava);
        assertThat(repository.due(SATURDAY_9_UTC.minusSeconds(2 * 3600), EIGHT)).extracting(Due::userId)
                .containsExactly(bratislava);
        assertThat(repository.due(MONDAY_8_30_UTC, EIGHT)).extracting(Due::userId).contains(weekdays, weekly, done);
        assertThat(repository.due(SATURDAY_9_UTC, EIGHT)).extracting(Due::day)
                .containsOnly(LocalDate.of(2026, 10, 24));
    }

    /** Задание сводки со своей почтой: отправитель и сервер; одна строка в письме. */
    private SendDigestHandler handler(ObjectProvider<JavaMailSender> sender, String from, String host) {
        DigestProperties properties = new DigestProperties(from, "http://localhost:5173", EIGHT, 1);
        return new SendDigestHandler(transactional(new DigestService(repository, sender, properties, delivery, host)));
    }

    /** Сервис, собранный в тесте, — без прокси Spring: транзакция — вокруг вызова, как у {@code @Transactional}. */
    private DigestService transactional(DigestService service) {
        return new DigestService(repository, mailSender, new DigestProperties("", "", EIGHT, 1), delivery, "") {
            @Override
            public boolean send(long userId, LocalDate day) {
                return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                        .execute(status -> service.send(userId, day)));
            }
        };
    }

    private long user(String email, String language, String frequency) {
        return jdbcTemplate.queryForObject("INSERT INTO user_account (email, password_hash, language, "
                + "digest_frequency) VALUES (?, '-', ?, ?) RETURNING id", Long.class, email, language, frequency);
    }

    private long condition(long user, String countries) {
        long condition = jdbcTemplate.queryForObject("INSERT INTO search_condition (user_id, countries, portion_limit, "
                + "active) VALUES (?, CAST(? AS text[]), 20, TRUE) RETURNING id", Long.class, user, countries);
        jdbcTemplate.update("INSERT INTO search_condition_position (search_condition_id, position_id) VALUES (?, ?)",
                condition, positionId);
        return condition;
    }

    /** Вакансия в Словакии (работодатель не указан), сопоставленная позиции; подтверждена {@code daysAgo} дней назад. */
    private long vacancy(String title, String state, int daysAgo) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, work_countries)
                VALUES (?, ?, 'https://example.com', now(), now() - make_interval(days => ?), '{SK}') RETURNING id
                """, Long.class, state, title, daysAgo);
        jdbcTemplate.update("""
                INSERT INTO vacancy_position_match (vacancy_id, position_id, dictionary_version, explanation)
                VALUES (?, ?, 'test', 'test')
                """, vacancy, positionId);
        return vacancy;
    }

    /** Вакансия выдана по условиям сейчас. */
    private long deliver(long condition, long vacancy) {
        jdbcTemplate.update("INSERT INTO delivered_vacancy (search_condition_id, vacancy_id, pass_run_id, delivered_at) "
                + "VALUES (?, ?, ?, clock_timestamp())", condition, vacancy, passRunId);
        return vacancy;
    }

    private void status(long user, long vacancy, String status) {
        jdbcTemplate.update("INSERT INTO vacancy_user_status (user_id, vacancy_id, status, changed_at) "
                + "VALUES (?, ?, ?, now())", user, vacancy, status);
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
