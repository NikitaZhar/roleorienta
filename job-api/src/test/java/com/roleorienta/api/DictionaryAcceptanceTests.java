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

    private static final String RUST = "{\"code\":\"test-rust\",\"name\":\"Rust developer\",\"group\":\"2512\",\"terms\":{"
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
        for (String table : new String[] {"position_search_miss", "delivered_vacancy", "pass_run", "vacancy_status_history",
                "vacancy_user_status", "search_condition", "digest_vacancy",
                "user_account", "spring_session_attributes",
                "spring_session"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("DELETE FROM position_term WHERE position_id IN (SELECT id FROM position WHERE code LIKE "
                + "'test-%')");
        jdbcTemplate.update("DELETE FROM position WHERE code LIKE 'test-%'");
        jdbcTemplate.update("DELETE FROM profesia_ad_position WHERE slug LIKE 'test-%'");
        jdbcTemplate.update("DELETE FROM profesia_position WHERE slug LIKE 'test-%'");
        jdbcTemplate.update("DELETE FROM job_posting WHERE source_id IN (SELECT id FROM source WHERE board = 'test-profesia')");
        jdbcTemplate.update("DELETE FROM vacancy WHERE primary_url LIKE 'https://test-profesia.example/%'");
        jdbcTemplate.update("DELETE FROM source WHERE board = 'test-profesia'");
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
        assertThat(added.body()).contains("\"code\":\"test-rust\",\"name\":\"Rust developer\","
                + "\"placement\":{\"group\":\"2512\",\"active\":true},"
                + "\"terms\":{\"titles\":[\"Rust developer\",\"rust engineer\"],\"roles\":[\"developer\"],"
                + "\"keywords\":[\"rust\"],\"exclude\":[]}");
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST).status()).isEqualTo(409);
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST.replace("test-rust", "Test Rust")).status())
                .isEqualTo(400);
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST.replace("test-rust", "test-empty")
                .replace("[\"Rust developer\",\" rust developer \",\"rust engineer\"]", "[]")).status()).isEqualTo(400);
        // Код из одних цифр — это код группы ISCO-08 в условиях: для позиции не допускается (аудит §211).
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST.replace("test-rust", "2512")).status())
                .isEqualTo(400);
        // Группа ISCO-08 обязательна и должна быть в справочнике (§204).
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST.replace("test-rust", "test-nogroup")
                .replace("\"group\":\"2512\",", "")).status()).isEqualTo(400);
        assertThat(admin.send("POST", "/api/v1/admin/positions", RUST.replace("test-rust", "test-nogroup")
                .replace("2512", "0000")).status()).isEqualTo(400);

        long id = positionId("test-rust");
        ApiTestClient.Response changed = admin.send("PUT", "/api/v1/admin/positions/" + id, "{\"name\":\"Rust engineer\","
                + "\"active\":true,\"group\":\"2519\",\"terms\":{\"titles\":[\"rust engineer\"],\"roles\":[],"
                + "\"keywords\":[],\"exclude\":[\"intern\"]}}");
        assertThat(changed.status()).isEqualTo(200);
        assertThat(version(changed.body())).isEqualTo(version + 2);
        assertThat(changed.body()).contains("\"name\":\"Rust engineer\",\"placement\":{\"group\":\"2519\","
                + "\"active\":true},\"terms\":{\"titles\":"
                + "[\"rust engineer\"],\"roles\":[],\"keywords\":[],\"exclude\":[\"intern\"]}");
        assertThat(admin.send("PUT", "/api/v1/admin/positions/999999999", "{\"name\":\"X\",\"active\":true,"
                + "\"group\":\"2512\",\"terms\":{\"titles\":[\"x\"],\"roles\":[],\"keywords\":[],\"exclude\":[]}}").status())
                .isEqualTo(404);
        // Без «active» позиция не выключается молча — неверный запрос (аудит §165).
        assertThat(admin.send("PUT", "/api/v1/admin/positions/" + id, "{\"name\":\"Rust engineer\","
                + "\"group\":\"2512\",\"terms\":{\"titles\":[\"rust engineer\"],\"roles\":[],\"keywords\":[],\"exclude\":[]}}")
                .status()).isEqualTo(400);

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
                + "\"group\":\"2512\",\"terms\":{\"titles\":[\"rust developer\"],\"roles\":[],\"keywords\":[],\"exclude\":[]}}").body())
                .contains("\"code\":\"test-rust\",\"name\":\"Rust developer\",\"placement\":{\"group\":\"2512\","
                        + "\"active\":false}");

        assertThat(chosen.send("GET", "/api/v1/positions?query=test-rust", null).body()).isEqualTo("[]");
        chosen.ifMatch = chosen.send("GET", "/api/v1/me/search-condition", null).etag();
        String remote = RUST_CONDITION.replace("]}", "],\"format\":\"REMOTE\"}");
        ApiTestClient.Response kept = chosen.send("PUT", "/api/v1/me/search-condition", remote);
        assertThat(kept.status()).isEqualTo(200);
        assertThat(kept.body()).contains("{\"code\":\"test-rust\",\"name\":\"Rust developer\",\"active\":false}");
        ApiTestClient other = ApiTestClient.signedIn(port(), "other@example.com");
        assertThat(other.send("PUT", "/api/v1/me/search-condition", RUST_CONDITION).body())
                .contains("\"pointer\":\"/positions\"", "Retired positions: [test-rust]");
    }

    /**
     * Что искали и не нашли (§205): запрос подсказки без результата запоминается — без пробелов по краям, в нижнем
     * регистре, с числом повторов; короче 2 знаков и запрос с результатом — нет; начало того же слова, записанное только
     * что, заменяется полной строкой; список — только администратору; запрос убирается из списка.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void administratorSeesSearchesWithoutResult() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        // «§» и эмодзи — один символ (в UTF-16 эмодзи — два): короче 2 знаков, не запоминаются (аудит §211).
        for (String query : new String[] {"zzqx", "%20ZZQX%20", "%C2%A7", "%F0%9F%98%80", "qwer", "qwerty", "lorry"}) {
            assertThat(user.send("GET", "/api/v1/positions?query=" + query, null).status()).isEqualTo(200);
        }
        // Без входа запрос не запоминается: подсказка открыта, список администратора не накручивается (аудит §211).
        assertThat(new ApiTestClient(port()).send("GET", "/api/v1/positions?query=zzanon", null).status())
                .isEqualTo(200);
        assertThat(user.send("GET", "/api/v1/admin/position-misses", null).status()).isEqualTo(403);

        ApiTestClient admin = admin();
        String misses = admin.send("GET", "/api/v1/admin/position-misses", null).body();
        assertThat(misses).startsWith("[{\"query\":\"zzqx\",\"misses\":2,").contains("{\"query\":\"qwerty\",\"misses\":1,")
                .doesNotContain("\"qwer\"", "§", "\uD83D\uDE00", "lorry", "zzanon");
        assertThat(admin.send("DELETE", "/api/v1/admin/position-misses?query=zzqx", null).status()).isEqualTo(204);
        assertThat(admin.send("DELETE", "/api/v1/admin/position-misses?query=zzqx", null).status()).isEqualTo(404);
        assertThat(admin.send("GET", "/api/v1/admin/position-misses", null).body()).doesNotContain("zzqx");
    }

    /**
     * Позиция profesia.sk без группы (§305): в списке — только с открытыми вакансиями, с числом вакансий и подсказкой
     * группы по другой позиции того же объявления; позиция с группой в список не входит; список и выбор
     * — только администратору; неизвестная группа — {@code 400}, неизвестная позиция — {@code 404}; выбранная группа
     * записана как выбор владельца, позиция уходит из списка.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void administratorGivesProfesiaPositionGroup() throws Exception {
        long source = jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES ('profesia', "
                + "'test-profesia') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO profesia_position (slug, name) VALUES ('test-autoklampiar', "
                + "'Autoklampiar/autoklampiarka'), ('test-no-ads', 'Bez objednávok')");
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', 'Autoklampiar', 'https://test-profesia.example/990001', now(), now()) RETURNING id""",
                Long.class);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at)
                VALUES (?, ?, 'profesia:990001', 'Autoklampiar', 'https://test-profesia.example/990001', now(), now())""",
                source, vacancy);
        jdbcTemplate.update("INSERT INTO profesia_ad_position (num, slug) VALUES (990001, 'test-autoklampiar')");
        // Вторая позиция того же объявления уже с группой — подсказка «1 of 1 ads».
        jdbcTemplate.update("INSERT INTO profesia_position (slug, name, code, mapped_by) VALUES ('test-klampiar', "
                + "'Klampiar', '7213', 'OWNER')");
        jdbcTemplate.update("INSERT INTO profesia_ad_position (num, slug) VALUES (990001, 'test-klampiar')");

        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        assertThat(user.send("GET", "/api/v1/admin/profesia-positions", null).status()).isEqualTo(403);
        assertThat(user.send("PUT", "/api/v1/admin/profesia-positions/test-autoklampiar", "{\"group\":\"7213\"}")
                .status()).isEqualTo(403);

        ApiTestClient admin = admin();
        String list = admin.send("GET", "/api/v1/admin/profesia-positions", null).body();
        assertThat(list).contains("{\"slug\":\"test-autoklampiar\",\"name\":\"Autoklampiar/autoklampiarka\","
                + "\"vacancies\":1,\"hints\":[").contains("{\"code\":\"7213\",")
                .contains("\"reason\":\"1 of 1 ads\"").doesNotContain("test-no-ads", "test-klampiar");
        assertThat(admin.send("PUT", "/api/v1/admin/profesia-positions/test-autoklampiar", "{\"group\":\"9999\"}")
                .status()).isEqualTo(400);
        assertThat(admin.send("PUT", "/api/v1/admin/profesia-positions/test-unknown", "{\"group\":\"7213\"}")
                .status()).isEqualTo(404);
        assertThat(admin.send("PUT", "/api/v1/admin/profesia-positions/test-autoklampiar", "{\"group\":\"7213\"}")
                .status()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForMap("SELECT code, mapped_by FROM profesia_position WHERE slug = "
                + "'test-autoklampiar'")).containsEntry("code", "7213").containsEntry("mapped_by", "OWNER");
        assertThat(admin.send("GET", "/api/v1/admin/profesia-positions", null).body()).doesNotContain("test-autoklampiar");
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
