package com.roleorienta.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Учётные записи и условия поиска через настоящий HTTP (бизнес-описание §3, §6, §7.4-А; технический
 * документ §8): фильтры Spring Security, сессии в PostgreSQL (Spring Session JDBC), cookie и CSRF —
 * как у SPA.
 *
 * <ul>
 *   <li>Вход: регистрация (и с языком экрана входа, §162), повтор email, неверный пароль, вход, язык интерфейса,
 *       частота сводки и часовой пояс в настройках (§164), выход; изменяющий запрос без
 *       CSRF-токена отклоняется.</li>
 *   <li>3 — смена условий: новая версия, прежний накопленный список не используется, статусы
 *       вакансий сохранены; смена только лимита список не сбрасывает; версия проверяется {@code If-Match}.</li>
 *   <li>14 (вход и условия) — без входа пользовательские функции недоступны; условия другого
 *       пользователя не видны.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AccountConditionAcceptanceTests {

    private static final String JAVA_SK = "{\"countries\":[\"sk\"],\"positions\":[\"java-developer\"]}";

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Чистые таблицы; позиции словаря (их пишет job-worker).
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "pass_run", "vacancy_status_history", "vacancy_user_status",
                "search_condition",
                "digest_vacancy",
                "user_account", "vacancy_position_match", "job_posting", "vacancy", "position_term", "position",
                "spring_session_attributes", "spring_session"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO position (code, name, occupation_code) VALUES "
                + "('java-developer', 'Java developer', '2512'), ('devops-engineer', 'DevOps engineer', '2512')");
    }

    /**
     * Регистрация, повтор email, неверный пароль, вход, текущий пользователь, выход.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void registerLoginLogout() throws Exception {
        ApiTestClient client = new ApiTestClient(port());
        assertThat(client.send("GET", "/api/v1/countries", null).body()).isEqualTo("[{\"code\":\"SK\",\"name\":\"Slovakia\"}]");
        assertThat(client.send("GET", "/api/v1/positions?query=java", null).body()).contains("java-developer");

        assertThat(client.send("POST", "/api/v1/auth/register", credentials("User@Example.com")).status())
                .isEqualTo(201);
        assertThat(client.send("POST", "/api/v1/auth/register", credentials("user@example.com")).status())
                .isEqualTo(409);
        assertThat(client.send("POST", "/api/v1/auth/login",
                "{\"email\":\"user@example.com\",\"password\":\"wrong-password\"}").status()).isEqualTo(401);
        assertThat(client.send("GET", "/api/v1/me", null).status()).isEqualTo(401);

        assertThat(client.send("POST", "/api/v1/auth/login", credentials("user@example.com")).status())
                .isEqualTo(200);
        ApiTestClient.Response me = client.send("GET", "/api/v1/me", null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains("\"email\":\"user@example.com\"", "\"language\":\"en\"");
        assertThat(client.send("PUT", "/api/v1/me/settings", "{\"language\":\"ru\"}").body())
                .contains("\"language\":\"ru\"");
        assertThat(client.send("GET", "/api/v1/me", null).body()).contains("\"language\":\"ru\"");
        assertThat(client.send("PUT", "/api/v1/me/settings", "{\"language\":\"de\"}").status()).isEqualTo(400);
        assertThat(me.body()).contains("\"digestFrequency\":\"DAILY\"");
        assertThat(client.send("PUT", "/api/v1/me/settings",
                "{\"digestFrequency\":\"WEEKDAYS\",\"timeZone\":\"Europe/Bratislava\"}").body())
                .contains("\"language\":\"ru\"", "\"digestFrequency\":\"WEEKDAYS\"");
        assertThat(jdbcTemplate.queryForObject("SELECT time_zone FROM user_account WHERE email = 'user@example.com'",
                String.class)).isEqualTo("Europe/Bratislava");
        assertThat(client.send("PUT", "/api/v1/me/settings", "{\"digestFrequency\":\"HOURLY\"}").status())
                .isEqualTo(400);
        assertThat(client.send("PUT", "/api/v1/me/settings", "{\"timeZone\":\"Mars/Olympus\"}").status())
                .isEqualTo(400);
        // Смещение вместо названия: PostgreSQL прочёл бы его со знаком наоборот (аудит §165).
        assertThat(client.send("PUT", "/api/v1/me/settings", "{\"timeZone\":\"+02:00\"}").body())
                .contains("\"pointer\":\"/timeZone\"");
        assertThat(client.send("GET", "/api/v1/me", null).body()).contains("\"timeZone\":\"Europe/Bratislava\"");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isPositive();

        assertThat(client.send("POST", "/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(client.send("GET", "/api/v1/me", null).status()).isEqualTo(401);

        ApiTestClient russian = new ApiTestClient(port());
        assertThat(russian.send("POST", "/api/v1/auth/register",
                "{\"email\":\"ru@example.com\",\"password\":\"password-123\",\"language\":\"ru\"}").body())
                .contains("\"language\":\"ru\"");
    }

    /**
     * Изменяющий запрос без CSRF-токена — {@code 403}; неверные поля — {@code 400} с указателем поля.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void rejectsRequestsWithoutCsrfTokenOrWithInvalidFields() throws Exception {
        ApiTestClient client = new ApiTestClient(port());
        client.withoutCsrf = true;
        assertThat(client.send("POST", "/api/v1/auth/register", credentials("user@example.com")).status())
                .isEqualTo(403);

        client.withoutCsrf = false;
        ApiTestClient.Response invalid = client.send("POST", "/api/v1/auth/register", "{\"email\":\"not-an-email\",\"password\":\"x\"}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"pointer\":\"/email\"").contains("\"pointer\":\"/password\"");
    }

    /**
     * Сценарий 3: смена позиции — новая версия, прежний список и место остановки не используются,
     * статус «Не интересно» сохранён (§156); смена только лимита — та же версия и тот же список;
     * {@code If-Match}: не указан — {@code 428}, устарел — {@code 412}; неподдерживаемая страна,
     * неизвестная позиция и неизвестный формат — {@code 400}; формат {@code HYBRID} — «любой» (§125); несколько
     * позиций (§126): тот же набор в другом порядке — та же версия, пустой набор — {@code 400}.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void scenario3ChangeOfConditionsStartsNewList() throws Exception {
        ApiTestClient client = signedIn("user@example.com");
        assertThat(client.send("GET", "/api/v1/me/search-condition", null).status()).isEqualTo(404);
        ApiTestClient.Response first = client.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(
                "{\"countries\":[\"SK\"],\"positions\":[{\"code\":\"java-developer\",\"name\":\"Java developer\","
                + "\"active\":true}],\"format\":null,\"portionLimit\":20}");
        long firstVersion = activeVersion();
        long vacancyId = deliveredAndMarked(firstVersion);

        client.ifMatch = null;
        assertThat(client.send("PUT", "/api/v1/me/search-condition", JAVA_SK).status()).isEqualTo(428);
        client.ifMatch = "\"0.20\"";
        assertThat(client.send("PUT", "/api/v1/me/search-condition", JAVA_SK).status()).isEqualTo(412);

        client.ifMatch = first.etag();
        ApiTestClient.Response limit = client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"java-developer\"],\"portionLimit\":5}");
        assertThat(limit.status()).isEqualTo(200);
        assertThat(activeVersion()).isEqualTo(firstVersion);
        assertThat(delivered(firstVersion)).isEqualTo(1);

        client.ifMatch = limit.etag();
        assertThat(client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"DE\"],\"positions\":[\"devops-engineer\"]}").body()).contains("\"pointer\":\"/countries\"");
        assertThat(client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"nobody\"]}").body()).contains("\"pointer\":\"/positions\"");
        assertThat(client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"devops-engineer\"],\"format\":\"WEEKEND\"}").status())
                .isEqualTo(400);
        ApiTestClient.Response changed = client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"devops-engineer\"],\"format\":\"REMOTE\"}");
        assertThat(changed.status()).isEqualTo(200);
        long secondVersion = activeVersion();
        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThat(delivered(secondVersion)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM search_condition WHERE active", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vacancy_user_status WHERE vacancy_id = ?",
                Integer.class, vacancyId)).isEqualTo(1);

        client.ifMatch = null;
        ApiTestClient.Response current = client.send("GET", "/api/v1/me/search-condition", null);
        assertThat(current.etag()).isEqualTo(changed.etag());
        assertThat(current.body()).contains("\"positions\":[{\"code\":\"devops-engineer\",\"name\":\"DevOps engineer\","
                + "\"active\":true}]", "\"format\":\"REMOTE\"");

        client.ifMatch = current.etag();
        ApiTestClient.Response hybrid = client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"devops-engineer\"],\"format\":\"HYBRID\"}");
        assertThat(hybrid.status()).isEqualTo(200);
        assertThat(hybrid.body()).contains("\"format\":null");

        client.ifMatch = hybrid.etag();
        ApiTestClient.Response two = client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"java-developer\",\"devops-engineer\"]}");
        assertThat(two.body()).contains("\"positions\":[{\"code\":\"devops-engineer\",", "},{\"code\":\"java-developer\",");
        long twoPositions = activeVersion();
        client.ifMatch = two.etag();
        assertThat(client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"devops-engineer\",\"java-developer\"]}").status())
                .isEqualTo(200);
        assertThat(activeVersion()).isEqualTo(twoPositions);
        assertThat(client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[]}").body()).contains("\"pointer\":\"/positions\"");
    }

    /**
     * Группы ISCO-08 в условиях (§204): подсказка — сначала позиции словаря, затем группы (по названию на языке
     * интерфейса и по синонимам ESCO, в том числе словацким), у группы — признак; группа сохраняется в условиях вместе с
     * позицией, название — на языке интерфейса; неизвестная группа — {@code 400}; окно «Все позиции» — направления,
     * группы и позиции словаря внутри группы.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void conditionsWithIscoGroup() throws Exception {
        ApiTestClient client = signedIn("user@example.com");
        client.language = "ru";
        assertThat(client.send("GET", "/api/v1/positions?query=java", null).body())
                .startsWith("[{\"code\":\"java-developer\",\"name\":\"Java developer\",\"group\":false}");
        assertThat(client.send("GET", "/api/v1/positions?query=" + URLEncoder.encode("грузовых", StandardCharsets.UTF_8), null).body())
                .contains("{\"code\":\"8332\",\"name\":\"Водители грузовых автомобилей\",\"group\":true}");
        assertThat(client.send("GET", "/api/v1/positions?query=" + URLEncoder.encode("nákladného", StandardCharsets.UTF_8), null).body()).contains("\"code\":\"8332\"");
        // С начала слова: «pyt» внутри «kopytá» (кузнецы, 7221) и «dopytu» (закупщики, 3323) не находит.
        assertThat(client.send("GET", "/api/v1/positions?query=pyt", null).body()).doesNotContain("7221", "3323");
        client.language = "en";
        assertThat(client.send("GET", "/api/v1/positions?query=lorry", null).body())
                .contains("{\"code\":\"8332\",\"name\":\"Heavy truck and lorry drivers\",\"group\":true}");

        client.language = "ru";
        ApiTestClient.Response saved = client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"java-developer\",\"8332\"]}");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body()).contains("\"positions\":[{\"code\":\"8332\",\"name\":\"Водители грузовых автомобилей\","
                + "\"active\":true},{\"code\":\"java-developer\",");
        assertThat(jdbcTemplate.queryForObject("SELECT code FROM search_condition_occupation", String.class))
                .isEqualTo("8332");
        client.language = "en";
        assertThat(client.send("GET", "/api/v1/me/search-condition", null).body())
                .contains("{\"code\":\"8332\",\"name\":\"Heavy truck and lorry drivers\",\"active\":true}");

        client.ifMatch = saved.etag();
        assertThat(client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"0000\"]}").body())
                .contains("\"pointer\":\"/positions\"", "Unknown positions: [0000]");

        client.language = "ru";
        String catalog = client.send("GET", "/api/v1/occupations", null).body();
        assertThat(catalog).contains("{\"code\":\"83\",\"name\":\"Водители и операторы подвижного оборудования\","
                + "\"groups\":[", "{\"code\":\"8332\",\"name\":\"Водители грузовых автомобилей\",\"positions\":[]}",
                "{\"code\":\"2512\",\"name\":\"Разработчики программного обеспечения\",\"positions\":[",
                "{\"code\":\"java-developer\",\"name\":\"Java developer\"}");
    }

    /**
     * Сценарий 14 (вход и условия): без входа — {@code 401}; второй пользователь не видит условий
     * первого и задаёт свои, не затрагивая их.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void scenario14ConditionsArePrivate() throws Exception {
        ApiTestClient anonymous = new ApiTestClient(port());
        assertThat(anonymous.send("GET", "/api/v1/me/search-condition", null).status()).isEqualTo(401);
        assertThat(anonymous.send("PUT", "/api/v1/me/search-condition", JAVA_SK).status()).isEqualTo(401);

        ApiTestClient first = signedIn("first@example.com");
        first.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        ApiTestClient second = signedIn("second@example.com");
        assertThat(second.send("GET", "/api/v1/me/search-condition", null).status()).isEqualTo(404);
        second.send("PUT", "/api/v1/me/search-condition", "{\"countries\":[\"SK\"],\"positions\":[\"devops-engineer\"]}");

        assertThat(first.send("GET", "/api/v1/me/search-condition", null).body()).contains("java-developer");
        assertThat(second.send("GET", "/api/v1/me/search-condition", null).body()).contains("devops-engineer");
    }

    private ApiTestClient signedIn(String email) throws IOException, InterruptedException {
        return ApiTestClient.signedIn(port(), email);
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private static String credentials(String email) {
        return ApiTestClient.credentials(email);
    }

    private long activeVersion() {
        return jdbcTemplate.queryForObject("SELECT id FROM search_condition WHERE active", Long.class);
    }

    private int delivered(long conditionId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM delivered_vacancy WHERE search_condition_id = ?",
                Integer.class, conditionId);
    }

    /**
     * Накопленный список версии из одной вакансии (выдаёт job-worker) и статус «Не интересно» у неё (§156).
     */
    private long deliveredAndMarked(long conditionId) {
        long vacancyId = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', 'Java Developer', 'https://example.com', now(), now()) RETURNING id
                """, Long.class);
        long passRunId = jdbcTemplate.queryForObject(
                "INSERT INTO pass_run (window_start) VALUES (now()) RETURNING id", Long.class);
        jdbcTemplate.update("""
                INSERT INTO delivered_vacancy (search_condition_id, vacancy_id, pass_run_id, delivered_at)
                VALUES (?, ?, ?, now())
                """, conditionId, vacancyId, passRunId);
        jdbcTemplate.update("INSERT INTO vacancy_user_status (user_id, vacancy_id, status, changed_at) "
                + "SELECT user_id, ?, 'NOT_INTERESTED', now() FROM search_condition WHERE id = ?", vacancyId, conditionId);
        return vacancyId;
    }
}
