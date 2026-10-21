package com.roleorienta.api;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Словарь позиций для администратора через настоящий HTTP (стенограмма §160): только роль администратора; новая
 * позиция, повтор кода, неверные поля; изменение и выключение — каждое изменение увеличивает номер версии;
 * выключенная позиция не предлагается при выборе и не выбирается заново, но остаётся в условиях, где уже выбрана;
 * общие настройки без повторов; предложения позиций (§161) — свои у пользователя, лимит в сутки, решения
 * администратора и их смена. Позиции теста — с кодом {@code test-…}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.admin.password=" + AdminMetricsAcceptanceTests.ADMIN_PASSWORD)
@Import(TestcontainersConfiguration.class)
class DictionaryAcceptanceTests {

    private static final String RUST = "{\"code\":\"test-rust\",\"name\":\"Rust developer\",\"terms\":{"
            + "\"titles\":[\"Rust developer\",\" rust developer \",\"rust engineer\"],\"roles\":[\"developer\"],"
            + "\"keywords\":[\"rust\"],\"exclude\":[]}}";
    private static final String RUST_CONDITION = "{\"countries\":[\"SK\"],\"positions\":[\"test-rust\"]}";

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Чистые учётные записи, условия, сессии; позиций теста нет.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"position_suggestion", "delivered_vacancy", "pass_run", "vacancy_status_history",
                "vacancy_user_status", "search_condition", "user_account", "spring_session_attributes",
                "spring_session"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("DELETE FROM position_term WHERE position_id IN (SELECT id FROM position WHERE code LIKE "
                + "'test-%')");
        jdbcTemplate.update("DELETE FROM position WHERE code LIKE 'test-%'");
    }

    /**
     * Без входа — {@code 401}, пользователь — {@code 403}.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void onlyAdministratorEditsDictionary() throws Exception {
        assertThat(new ApiTestClient(port()).send("GET", "/api/v1/admin/dictionary", null).status()).isEqualTo(401);
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        assertThat(user.send("GET", "/api/v1/admin/dictionary", null).status()).isEqualTo(403);
        assertThat(user.send("POST", "/api/v1/admin/positions", RUST).status()).isEqualTo(403);
    }

    /**
     * Новая позиция: названия без повторов и пробелов по краям, номер версии +1; повтор кода — {@code 409}; неверный
     * код и пустые эквивалентные названия — {@code 400}; изменение — версия +1, названия заменены; неизвестная
     * позиция — {@code 404}; общие настройки без повторов.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void administratorAddsAndChangesPositions() throws Exception {
        ApiTestClient admin = admin();
        int version = version(admin.send("GET", "/api/v1/admin/dictionary", null).body());

        ApiTestClient.Response added = admin.send("POST", "/api/v1/admin/positions", RUST);
        assertThat(added.status()).isEqualTo(201);
        assertThat(version(added.body())).isEqualTo(version + 1);
        assertThat(added.body()).contains("\"code\":\"test-rust\",\"name\":\"Rust developer\",\"active\":true,"
                + "\"terms\":{\"titles\":[\"Rust developer\",\"rust engineer\"],\"roles\":[\"developer\"],"
                + "\"keywords\":[\"rust\"],\"exclude\":[]}");
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST).status()).isEqualTo(409);
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST.replace("test-rust", "Test Rust")).status())
                .isEqualTo(400);
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST.replace("test-rust", "test-empty")
                .replace("[\"Rust developer\",\" rust developer \",\"rust engineer\"]", "[]")).status()).isEqualTo(400);

        long id = positionId("test-rust");
        ApiTestClient.Response changed = admin.send("PUT", "/api/v1/admin/positions/" + id, "{\"name\":\"Rust engineer\","
                + "\"active\":true,\"terms\":{\"titles\":[\"rust engineer\"],\"roles\":[],\"keywords\":[],\"exclude\":"
                + "[\"intern\"]}}");
        assertThat(changed.status()).isEqualTo(200);
        assertThat(version(changed.body())).isEqualTo(version + 2);
        assertThat(changed.body()).contains("\"name\":\"Rust engineer\",\"active\":true,\"terms\":{\"titles\":"
                + "[\"rust engineer\"],\"roles\":[],\"keywords\":[],\"exclude\":[\"intern\"]}");
        assertThat(admin.send("PUT", "/api/v1/admin/positions/999999999", "{\"name\":\"X\",\"active\":true,"
                + "\"terms\":{\"titles\":[\"x\"],\"roles\":[],\"keywords\":[],\"exclude\":[]}}").status())
                .isEqualTo(404);

        ApiTestClient.Response settings = admin.send("PUT", "/api/v1/admin/dictionary",
                "{\"contentHits\":2,\"otherSpecializations\":[\"ruby\",\"Ruby\",\"php\"]}");
        assertThat(settings.status()).isEqualTo(200);
        assertThat(settings.body()).contains("\"version\":" + (version + 3) + ",\"contentHits\":2,"
                + "\"otherSpecializations\":[\"ruby\",\"php\"]");
        assertThat(admin.send("PUT", "/api/v1/admin/dictionary", "{\"contentHits\":0,\"otherSpecializations\":[]}")
                .status()).isEqualTo(400);
    }

    /**
     * Выключение (решение владельца 2026-10-21): позиция не предлагается при выборе и не выбирается заново; у того,
     * кто её уже выбрал, остаётся — условия сохраняются и с ней.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void retiredPositionStaysOnlyWhereChosen() throws Exception {
        ApiTestClient admin = admin();
        admin.send("POST", "/api/v1/admin/positions", RUST);
        long id = positionId("test-rust");
        ApiTestClient chosen = ApiTestClient.signedIn(port(), "chosen@example.com");
        assertThat(chosen.send("GET", "/api/v1/positions?query=test-rust", null).body()).contains("test-rust");
        assertThat(chosen.send("PUT", "/api/v1/me/search-condition", RUST_CONDITION).status()).isEqualTo(200);

        assertThat(admin.send("PUT", "/api/v1/admin/positions/" + id, "{\"name\":\"Rust developer\",\"active\":false,"
                + "\"terms\":{\"titles\":[\"rust developer\"],\"roles\":[],\"keywords\":[],\"exclude\":[]}}").body())
                .contains("\"code\":\"test-rust\",\"name\":\"Rust developer\",\"active\":false");

        assertThat(chosen.send("GET", "/api/v1/positions?query=test-rust", null).body()).isEqualTo("[]");
        chosen.ifMatch = chosen.send("GET", "/api/v1/me/search-condition", null).etag();
        String remote = RUST_CONDITION.replace("]}", "],\"format\":\"REMOTE\"}");
        assertThat(chosen.send("PUT", "/api/v1/me/search-condition", remote).status()).isEqualTo(200);
        ApiTestClient other = ApiTestClient.signedIn(port(), "other@example.com");
        assertThat(other.send("PUT", "/api/v1/me/search-condition", RUST_CONDITION).body())
                .contains("\"pointer\":\"/positions\"", "Retired positions: [test-rust]");
    }

    /**
     * Предложение позиции (§161): на рассмотрении, видно только автору; администратор видит все с почтой; «добавлена»
     * — только с позицией из словаря, «на рассмотрении» решением не бывает; решение меняется на «отклонена» с
     * причиной; нет предложения — {@code 404}; пользователю решения недоступны.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void userSuggestsAndAdministratorDecides() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        ApiTestClient.Response suggested = user.send("POST", "/api/v1/me/position-suggestions",
                "{\"text\":\" Rust developer: Senior Rust Engineer \"}");
        assertThat(suggested.status()).isEqualTo(201);
        assertThat(suggested.body()).contains("\"text\":\"Rust developer: Senior Rust Engineer\"",
                "\"state\":\"PENDING\",\"positionCode\":null,\"positionName\":null,\"reason\":null,"
                        + "\"decidedAt\":null");
        assertThat(ApiTestClient.signedIn(port(), "other@example.com")
                .send("GET", "/api/v1/me/position-suggestions", null).body()).isEqualTo("[]");
        long id = jdbcTemplate.queryForObject("SELECT id FROM position_suggestion", Long.class);
        String decision = "/api/v1/admin/position-suggestions/" + id;
        assertThat(user.send("PUT", decision, "{\"decision\":\"REJECTED\"}").status()).isEqualTo(403);

        ApiTestClient admin = admin();
        admin.send("POST", "/api/v1/admin/positions", RUST);
        assertThat(admin.send("GET", "/api/v1/admin/position-suggestions", null).body())
                .contains("\"email\":\"user@example.com\"", "\"state\":\"PENDING\"");
        assertThat(admin.send("PUT", decision, "{\"decision\":\"ADDED\",\"position\":\"test-unknown\"}").status())
                .isEqualTo(400);
        assertThat(admin.send("PUT", decision, "{\"decision\":\"PENDING\"}").status()).isEqualTo(400);
        assertThat(admin.send("PUT", decision, "{\"decision\":\"ADDED\",\"position\":\"test-rust\"}").status())
                .isEqualTo(204);
        assertThat(user.send("GET", "/api/v1/me/position-suggestions", null).body()).contains(
                "\"state\":\"ADDED\",\"positionCode\":\"test-rust\",\"positionName\":\"Rust developer\"");

        assertThat(admin.send("PUT", decision, "{\"decision\":\"REJECTED\",\"reason\":\" Too narrow \"}").status())
                .isEqualTo(204);
        assertThat(user.send("GET", "/api/v1/me/position-suggestions", null).body()).contains(
                "\"state\":\"REJECTED\",\"positionCode\":null,\"positionName\":null,\"reason\":\"Too narrow\"");
        assertThat(admin.send("PUT", "/api/v1/admin/position-suggestions/999999999", "{\"decision\":\"REJECTED\"}")
                .status()).isEqualTo(404);
    }

    /**
     * Не больше 10 предложений за сутки — дальше {@code 429}; пустое — {@code 400}.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void suggestionsAreLimitedPerDay() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        for (int number = 1; number <= 10; number++) {
            assertThat(user.send("POST", "/api/v1/me/position-suggestions", "{\"text\":\"Position " + number
                    + "\"}").status()).isEqualTo(201);
        }
        assertThat(user.send("POST", "/api/v1/me/position-suggestions", "{\"text\":\"One more\"}").status())
                .isEqualTo(429);
        assertThat(user.send("POST", "/api/v1/me/position-suggestions", "{\"text\":\" \"}").status())
                .isEqualTo(400);
    }

    private ApiTestClient admin() throws Exception {
        ApiTestClient admin = new ApiTestClient(port());
        assertThat(admin.send("POST", "/api/v1/admin/login", "{\"username\":\"admin\",\"password\":\""
                + AdminMetricsAcceptanceTests.ADMIN_PASSWORD + "\"}").status()).isEqualTo(200);
        return admin;
    }

    private long positionId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM position WHERE code = ?", Long.class, code);
    }

    private static int version(String body) {
        Matcher matcher = Pattern.compile("\"version\":(\\d+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
