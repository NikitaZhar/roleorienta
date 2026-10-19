package com.roleorienta.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Отдельный вход администратора и показатели ядра через настоящий HTTP (§132; бизнес-описание §7.3): без входа —
 * {@code 401}, пользователь — {@code 403}, администратор видит таблицу показателей и не видит пользовательских
 * функций.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.admin.password=" + AdminMetricsAcceptanceTests.ADMIN_PASSWORD)
@Import(TestcontainersConfiguration.class)
class AdminMetricsAcceptanceTests {

    static final String ADMIN_PASSWORD = "admin-secret-1";

    private static final String METRICS = "/api/v1/admin/metrics?country=SK&days=14";

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Чистые учётные записи и сессии; показатели двух дней: у второго дня нет разреза «BRAND».
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "pass_run", "unsuitable_mark", "search_condition",
                "user_account", "spring_session_attributes", "spring_session", "core_metric_daily"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("""
                INSERT INTO core_metric_daily (day, country, metric, dimension, value) VALUES
                ('2026-10-17', 'SK', 'companies_known', '', 11),
                ('2026-10-18', 'SK', 'companies_known', '', 12),
                ('2026-10-18', 'SK', 'companies_with_site', 'BRAND', 3),
                ('2026-10-18', '', 'operations_failed', 'READ_SOURCE', 1)""");
    }

    /**
     * Без входа — {@code 401}; неверный пароль — {@code 401}; пользователь — {@code 403}.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void onlyAdministratorSeesMetrics() throws Exception {
        ApiTestClient anonymous = new ApiTestClient(port());
        assertThat(anonymous.send("GET", METRICS, null).status()).isEqualTo(401);
        assertThat(anonymous.send("POST", "/api/v1/admin/login", admin("wrong-password")).status()).isEqualTo(401);

        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        assertThat(user.send("GET", METRICS, null).status()).isEqualTo(403);
    }

    /**
     * Администратор: таблица страны по дням (новые первыми, нет значения — {@code null}), список стран; функции
     * пользователя — {@code 403}; выход закрывает доступ.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void administratorSeesMetricsTable() throws Exception {
        ApiTestClient client = new ApiTestClient(port());
        assertThat(client.send("POST", "/api/v1/admin/login", admin(ADMIN_PASSWORD)).status()).isEqualTo(200);

        ApiTestClient.Response table = client.send("GET", METRICS, null);

        assertThat(table.status()).isEqualTo(200);
        assertThat(table.body()).contains("\"country\":\"SK\"", "\"countries\":[\"\",\"SK\"]",
                "\"days\":[\"2026-10-18\",\"2026-10-17\"]",
                "{\"metric\":\"companies_known\",\"dimension\":\"\",\"values\":[12,11]}",
                "{\"metric\":\"companies_with_site\",\"dimension\":\"BRAND\",\"values\":[3,null]}");
        assertThat(client.send("GET", "/api/v1/admin/me", null).status()).isEqualTo(200);
        assertThat(client.send("GET", "/api/v1/me", null).status()).isEqualTo(403);

        assertThat(client.send("POST", "/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(client.send("GET", METRICS, null).status()).isEqualTo(401);
    }

    private static String admin(String password) {
        return "{\"username\":\"admin\",\"password\":\"" + password + "\"}";
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
