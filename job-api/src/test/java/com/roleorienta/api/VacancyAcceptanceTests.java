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
 * Накопленный список, сведения о вакансии и отметки «не подходит» через настоящий HTTP (бизнес-описание §4.5, §6,
 * §7.4; технический документ §8). Выдачу (job-worker) заменяют строки {@code delivered_vacancy}.
 *
 * <ul>
 *   <li>Список: новые сверху, страницы по курсору; закрытая и давно не подтверждённая вакансия не видна.</li>
 *   <li>Сведения: работодатель (компания источника), страна, формат, ссылка, даты, состояние.</li>
 *   <li>13 — отметка «не подходит» скрывает вакансию у этого пользователя, сохраняется при смене условий, после
 *       снятия вакансия возвращается; повтор отметки и снятия — без ошибки.</li>
 *   <li>14 (список) — вакансии и отметки другого пользователя недоступны ({@code 404}); курсор другого списка —
 *       {@code 400}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class VacancyAcceptanceTests {

    private static final String JAVA_SK = "{\"countries\":[\"SK\"],\"position\":\"java-developer\"}";
    private static final Pattern CURSOR = Pattern.compile("\"nextCursor\":\"([^\"]+)\"");

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long passRunId;

    /**
     * Чистые таблицы; позиции словаря; проход выдачи.
     */
    @BeforeEach
    void setUp() {
        for (String table : new String[] {"delivered_vacancy", "pass_run", "unsuitable_mark", "search_condition",
                "user_account", "vacancy_position_match", "job_posting", "vacancy", "position",
                "spring_session_attributes", "spring_session"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO position (code, name) VALUES ('java-developer', 'Java developer'), "
                + "('devops-engineer', 'DevOps engineer')");
        passRunId = jdbcTemplate.queryForObject("INSERT INTO pass_run (window_start) VALUES (now()) RETURNING id",
                Long.class);
    }

    /**
     * Список страницами, сведения (источник без компании — работодатель по названию у системы найма, аудит §65),
     * сценарий 13 — отметка, смена условий, снятие.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void scenario13ListDetailsAndUnsuitableMark() throws Exception {
        ApiTestClient client = ApiTestClient.signedIn(port(), "user@example.com");
        client.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        long condition = activeCondition();
        long first = delivered(condition, "First", "ACTIVE", 0, 3);
        long second = delivered(condition, "Second", "ACTIVE", 0, 2);
        delivered(condition, "Third", "ACTIVE", 0, 1);
        delivered(condition, "Closed", "CLOSED", 0, 0);
        delivered(condition, "Unconfirmed", "ACTIVE", 40, 0);

        ApiTestClient.Response page = client.send("GET", "/api/v1/me/vacancies?limit=2", null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(titles(page.body())).isEqualTo("Third,Second");
        ApiTestClient.Response next = client.send("GET", "/api/v1/me/vacancies?limit=2&cursor=" + cursor(page.body()),
                null);
        assertThat(titles(next.body())).isEqualTo("First");
        assertThat(next.body()).contains("\"nextCursor\":null");
        ApiTestClient.Response whole = client.send("GET", "/api/v1/me/vacancies?limit=3", null);
        assertThat(titles(whole.body())).isEqualTo("Third,Second,First");
        assertThat(whole.body()).contains("\"nextCursor\":null");

        ApiTestClient.Response details = client.send("GET", "/api/v1/me/vacancies/" + first, null);
        assertThat(details.status()).isEqualTo(200);
        assertThat(details.body()).contains("\"position\":\"First\"", "\"employer\":\"Employer Ltd\"",
                "\"agency\":null", "\"countries\":[\"SK\"]", "\"format\":null", "\"countryUncertain\":false",
                "\"url\":\"https://example.com/First\"", "\"state\":\"ACTIVE\"", "\"unsuitable\":false");
        jdbcTemplate.update("DELETE FROM company_source WHERE source_id = (SELECT id FROM source WHERE board = ?)",
                "vacancy-test-second");
        jdbcTemplate.update("UPDATE source SET employer_name = 'Second Group' WHERE board = ?", "vacancy-test-second");
        assertThat(client.send("GET", "/api/v1/me/vacancies/" + second, null).body())
                .contains("\"employer\":\"Second Group\"");

        assertThat(client.send("PUT", "/api/v1/me/vacancies/" + second + "/unsuitable", null).status()).isEqualTo(204);
        assertThat(client.send("PUT", "/api/v1/me/vacancies/" + second + "/unsuitable", null).status()).isEqualTo(204);
        assertThat(titles(client.send("GET", "/api/v1/me/vacancies", null).body())).isEqualTo("Third,First");
        assertThat(titles(client.send("GET", "/api/v1/me/unsuitable", null).body())).isEqualTo("Second");

        client.ifMatch = client.send("GET", "/api/v1/me/search-condition", null).etag();
        client.send("PUT", "/api/v1/me/search-condition", "{\"countries\":[\"SK\"],\"position\":\"devops-engineer\"}");
        assertThat(titles(client.send("GET", "/api/v1/me/vacancies", null).body())).isEmpty();
        assertThat(titles(client.send("GET", "/api/v1/me/unsuitable", null).body())).isEqualTo("Second");
        assertThat(client.send("GET", "/api/v1/me/vacancies/" + second, null).body()).contains("\"unsuitable\":true");

        assertThat(client.send("DELETE", "/api/v1/me/vacancies/" + second + "/unsuitable", null).status())
                .isEqualTo(204);
        assertThat(client.send("DELETE", "/api/v1/me/vacancies/" + second + "/unsuitable", null).status())
                .isEqualTo(204);
        assertThat(titles(client.send("GET", "/api/v1/me/unsuitable", null).body())).isEmpty();
    }

    /**
     * Сценарий 14 (список): вакансия первого пользователя второму недоступна — сведения и отметка {@code 404};
     * без условий список пуст; курсор чужого списка — {@code 400} (и в накопленном списке, где у второго пользователя
     * нет условий, и в отмеченных).
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void scenario14VacanciesArePrivate() throws Exception {
        ApiTestClient owner = ApiTestClient.signedIn(port(), "owner@example.com");
        owner.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        long condition = activeCondition();
        delivered(condition, "Older", "ACTIVE", 0, 2);
        long vacancy = delivered(condition, "Own", "ACTIVE", 0, 1);
        String ownerCursor = cursor(owner.send("GET", "/api/v1/me/vacancies?limit=1", null).body());

        ApiTestClient other = ApiTestClient.signedIn(port(), "other@example.com");
        assertThat(other.send("GET", "/api/v1/me/vacancies", null).body())
                .isEqualTo("{\"items\":[],\"nextCursor\":null}");
        assertThat(other.send("GET", "/api/v1/me/vacancies/" + vacancy, null).status()).isEqualTo(404);
        assertThat(other.send("PUT", "/api/v1/me/vacancies/" + vacancy + "/unsuitable", null).status()).isEqualTo(404);
        assertThat(other.send("GET", "/api/v1/me/vacancies?cursor=" + ownerCursor, null).status()).isEqualTo(400);
        assertThat(other.send("GET", "/api/v1/me/unsuitable?cursor=" + ownerCursor, null).status()).isEqualTo(400);
        assertThat(new ApiTestClient(port()).send("GET", "/api/v1/me/vacancies", null).status()).isEqualTo(401);
    }

    /**
     * Вакансия с публикацией в источнике компании-работодателя, подходящая позиции «Java developer», выданная в
     * список версии условий.
     *
     * @param daysUnconfirmed сколько дней не подтверждалась
     * @param hoursAgo        сколько часов назад выдана (меньше — новее)
     */
    private long delivered(long condition, String title, String state, int daysUnconfirmed, int hoursAgo) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, work_countries,
                                     country_uncertain)
                VALUES (?, ?, ?, now() - interval '60 days', now() - make_interval(days => ?), ARRAY['SK'], FALSE)
                RETURNING id
                """, Long.class, state, title, "https://example.com/" + title, daysUnconfirmed);
        jdbcTemplate.update("""
                INSERT INTO vacancy_position_match (vacancy_id, position_id, dictionary_version, explanation)
                SELECT ?, id, 'test', 'title' FROM position WHERE code = 'java-developer'
                """, vacancy);
        long source = jdbcTemplate.queryForObject("""
                INSERT INTO source (provider, board, country) VALUES ('greenhouse', ?, 'SK')
                ON CONFLICT (provider, board) DO UPDATE SET country = EXCLUDED.country RETURNING id
                """, Long.class, "vacancy-test-" + title.toLowerCase());
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                """, source, vacancy, title, title, "https://example.com/" + title);
        long company = jdbcTemplate.queryForObject("""
                INSERT INTO company (country, registration_number, name, registry) VALUES ('SK', '99900001', 'Employer Ltd', 'RPO')
                ON CONFLICT (country, registration_number) DO UPDATE SET name = EXCLUDED.name RETURNING id
                """, Long.class);
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id, role) VALUES (?, ?, 'EMPLOYER') "
                + "ON CONFLICT DO NOTHING", company, source);
        jdbcTemplate.update("""
                INSERT INTO delivered_vacancy (search_condition_id, vacancy_id, pass_run_id, delivered_at)
                VALUES (?, ?, ?, now() - make_interval(hours => ?))
                """, condition, vacancy, passRunId, hoursAgo);
        return vacancy;
    }

    private long activeCondition() {
        return jdbcTemplate.queryForObject("SELECT id FROM search_condition WHERE active", Long.class);
    }

    private static String titles(String body) {
        Matcher matcher = Pattern.compile("\"position\":\"([^\"]+)\"").matcher(body);
        StringBuilder titles = new StringBuilder();
        while (matcher.find()) {
            titles.append(titles.isEmpty() ? "" : ",").append(matcher.group(1));
        }
        return titles.toString();
    }

    private static String cursor(String body) {
        Matcher matcher = CURSOR.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
