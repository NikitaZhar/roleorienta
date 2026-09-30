package com.roleorienta.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   <li>Вход: регистрация, повтор email, неверный пароль, вход, выход; изменяющий запрос без
 *       CSRF-токена отклоняется.</li>
 *   <li>3 — смена условий: новая версия, прежний накопленный список не используется, отметки
 *       сохранены; смена только лимита список не сбрасывает; версия проверяется {@code If-Match}.</li>
 *   <li>14 (вход и условия) — без входа пользовательские функции недоступны; условия другого
 *       пользователя не видны.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AccountConditionAcceptanceTests {

    private static final String PASSWORD = "secret-password";
    private static final String JAVA_SK = "{\"countries\":[\"sk\"],\"position\":\"java-developer\"}";

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Чистые таблицы; позиции словаря (их пишет job-worker).
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "pass_run", "unsuitable_mark", "search_condition",
                "user_account", "vacancy", "position", "spring_session_attributes", "spring_session"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO position (code, name) VALUES ('java-developer', 'Java developer'), "
                + "('devops-engineer', 'DevOps engineer')");
    }

    /**
     * Регистрация, повтор email, неверный пароль, вход, текущий пользователь, выход.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void registerLoginLogout() throws Exception {
        Client client = new Client();
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
        Response me = client.send("GET", "/api/v1/me", null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains("\"email\":\"user@example.com\"");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isPositive();

        assertThat(client.send("POST", "/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(client.send("GET", "/api/v1/me", null).status()).isEqualTo(401);
    }

    /**
     * Изменяющий запрос без CSRF-токена — {@code 403}; неверные поля — {@code 400} с указателем поля.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void rejectsRequestsWithoutCsrfTokenOrWithInvalidFields() throws Exception {
        Client client = new Client();
        client.withoutCsrf = true;
        assertThat(client.send("POST", "/api/v1/auth/register", credentials("user@example.com")).status())
                .isEqualTo(403);

        client.withoutCsrf = false;
        Response invalid = client.send("POST", "/api/v1/auth/register", "{\"email\":\"not-an-email\",\"password\":\"x\"}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"pointer\":\"/email\"").contains("\"pointer\":\"/password\"");
    }

    /**
     * Сценарий 3: смена позиции — новая версия, прежний список и место остановки не используются,
     * отметка «не подходит» сохранена; смена только лимита — та же версия и тот же список;
     * {@code If-Match}: не указан — {@code 428}, устарел — {@code 412}; неподдерживаемая страна и
     * неизвестная позиция — {@code 400}.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void scenario3ChangeOfConditionsStartsNewList() throws Exception {
        Client client = signedIn("user@example.com");
        assertThat(client.send("GET", "/api/v1/me/search-condition", null).status()).isEqualTo(404);
        Response first = client.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(
                "{\"countries\":[\"SK\"],\"position\":\"java-developer\",\"format\":null,\"portionLimit\":20}");
        long firstVersion = activeVersion();
        long vacancyId = deliveredAndMarked(firstVersion);

        client.ifMatch = null;
        assertThat(client.send("PUT", "/api/v1/me/search-condition", JAVA_SK).status()).isEqualTo(428);
        client.ifMatch = "\"0.20\"";
        assertThat(client.send("PUT", "/api/v1/me/search-condition", JAVA_SK).status()).isEqualTo(412);

        client.ifMatch = first.etag();
        Response limit = client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"position\":\"java-developer\",\"portionLimit\":5}");
        assertThat(limit.status()).isEqualTo(200);
        assertThat(activeVersion()).isEqualTo(firstVersion);
        assertThat(delivered(firstVersion)).isEqualTo(1);

        client.ifMatch = limit.etag();
        assertThat(client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"DE\"],\"position\":\"devops-engineer\"}").body()).contains("\"pointer\":\"/countries\"");
        assertThat(client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"position\":\"nobody\"}").body()).contains("\"pointer\":\"/position\"");
        Response changed = client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"position\":\"devops-engineer\",\"format\":\"REMOTE\"}");
        assertThat(changed.status()).isEqualTo(200);
        long secondVersion = activeVersion();
        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThat(delivered(secondVersion)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM search_condition WHERE active", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM unsuitable_mark WHERE vacancy_id = ?",
                Integer.class, vacancyId)).isEqualTo(1);

        client.ifMatch = null;
        Response current = client.send("GET", "/api/v1/me/search-condition", null);
        assertThat(current.etag()).isEqualTo(changed.etag());
        assertThat(current.body()).contains("\"position\":\"devops-engineer\"", "\"format\":\"REMOTE\"");
    }

    /**
     * Сценарий 14 (вход и условия): без входа — {@code 401}; второй пользователь не видит условий
     * первого и задаёт свои, не затрагивая их.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void scenario14ConditionsArePrivate() throws Exception {
        Client anonymous = new Client();
        assertThat(anonymous.send("GET", "/api/v1/me/search-condition", null).status()).isEqualTo(401);
        assertThat(anonymous.send("PUT", "/api/v1/me/search-condition", JAVA_SK).status()).isEqualTo(401);

        Client first = signedIn("first@example.com");
        first.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        Client second = signedIn("second@example.com");
        assertThat(second.send("GET", "/api/v1/me/search-condition", null).status()).isEqualTo(404);
        second.send("PUT", "/api/v1/me/search-condition", "{\"countries\":[\"SK\"],\"position\":\"devops-engineer\"}");

        assertThat(first.send("GET", "/api/v1/me/search-condition", null).body()).contains("java-developer");
        assertThat(second.send("GET", "/api/v1/me/search-condition", null).body()).contains("devops-engineer");
    }

    private Client signedIn(String email) throws IOException, InterruptedException {
        Client client = new Client();
        client.send("POST", "/api/v1/auth/register", credentials(email));
        assertThat(client.send("POST", "/api/v1/auth/login", credentials(email)).status()).isEqualTo(200);
        return client;
    }

    private static String credentials(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private long activeVersion() {
        return jdbcTemplate.queryForObject("SELECT id FROM search_condition WHERE active", Long.class);
    }

    private int delivered(long conditionId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM delivered_vacancy WHERE search_condition_id = ?",
                Integer.class, conditionId);
    }

    /**
     * Накопленный список версии из одной вакансии (выдаёт job-worker) и отметка «не подходит» на ней.
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
        jdbcTemplate.update("INSERT INTO unsuitable_mark (user_id, vacancy_id) SELECT user_id, ? FROM search_condition "
                + "WHERE id = ?", vacancyId, conditionId);
        return vacancyId;
    }

    /**
     * Ответ: код, тело, {@code ETag}.
     *
     * @param status код
     * @param body   тело
     * @param etag   заголовок {@code ETag}; {@code null} — нет
     */
    private record Response(int status, String body, String etag) {
    }

    /**
     * Клиент как SPA: хранит cookie (сессия, CSRF-токен) и возвращает токен заголовком
     * {@code X-XSRF-TOKEN} в изменяющих запросах; {@code If-Match} — если задан.
     */
    private final class Client {

        private static final Pattern COOKIE = Pattern.compile("^([^=]+)=([^;]*)");

        private final HttpClient http = HttpClient.newHttpClient();
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private boolean withoutCsrf;
        private String ifMatch;

        Response send(String method, String path, String body) throws IOException, InterruptedException {
            if (!"GET".equals(method) && !withoutCsrf && !cookies.containsKey("XSRF-TOKEN")) {
                send("GET", "/api/v1/countries", null);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json");
            if (!cookies.isEmpty()) {
                request.header("Cookie", String.join("; ", cookies.entrySet().stream()
                        .map(cookie -> cookie.getKey() + "=" + cookie.getValue()).toList()));
            }
            if (!"GET".equals(method) && !withoutCsrf) {
                request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            if (ifMatch != null && "PUT".equals(method)) {
                request.header("If-Match", ifMatch);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String setCookie : response.headers().allValues("set-cookie")) {
                Matcher cookie = COOKIE.matcher(setCookie);
                if (cookie.find()) {
                    if (cookie.group(2).isEmpty() || setCookie.contains("Max-Age=0")) {
                        cookies.remove(cookie.group(1));
                    } else {
                        cookies.put(cookie.group(1), cookie.group(2));
                    }
                }
            }
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("etag").orElse(null));
        }
    }
}
