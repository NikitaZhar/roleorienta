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
 * Результаты поиска, сведения о вакансии и статусы вакансии у пользователя через настоящий HTTP (бизнес-описание §4.5,
 * §6, §7.4; технический документ §8; стенограмма §156). Выдачу (job-worker) заменяют строки {@code delivered_vacancy}.
 *
 * <ul>
 *   <li>Список: открытые позже сверху, при равной дате — больший id выше; страницы по номеру с общим числом (§151);
 *       закрытая и давно не подтверждённая вакансия не видна; видна вакансия со страной условий среди определённых
 *       стран или без определённой страны (с пометкой), только «*» — нет (§148, аудит §154).</li>
 *   <li>Сведения: работодатель (компания источника), страна, формат, ссылка, дата открытия, статус и история.</li>
 *   <li>13 — «Не интересно» скрывает вакансию у этого пользователя, сохраняется при смене условий, после «Вернуть»
 *       вакансия возвращается; повтор — без ошибки.</li>
 *   <li>Статусы: «Новая» до открытия сведений, «новых M»; отклик уводит вакансию в «Мои отклики», статус того же
 *       дня заменяет прежний выбор; закрытая работодателем остаётся в «Моих откликах» со строкой истории.</li>
 *   <li>14 (список) — вакансии и статусы другого пользователя недоступны ({@code 404}), их списки у него пусты.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class VacancyAcceptanceTests {

    private static final String JAVA_SK = "{\"countries\":[\"SK\"],\"positions\":[\"java-developer\"]}";
    private static final String EMPTY_PAGE = "{\"items\":[],\"page\":1,\"size\":20,\"total\":0,\"newCount\":0}";

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
        for (String table : new String[] {"delivered_vacancy", "pass_run", "vacancy_status_history", "vacancy_user_status",
                "search_condition",
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
     * Список страницами по номеру с общим числом (§151), сведения (источник без компании — работодатель по названию у
     * системы найма, аудит §65), сценарий 13 — «Не интересно», смена условий, «Вернуть».
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void scenario13ListDetailsAndNotInterested() throws Exception {
        ApiTestClient client = ApiTestClient.signedIn(port(), "user@example.com");
        client.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        long condition = activeCondition();
        long first = delivered(condition, "First", "ACTIVE", 0, 3);
        long second = delivered(condition, "Second", "ACTIVE", 0, 2);
        delivered(condition, "Third", "ACTIVE", 0, 1);
        delivered(condition, "Closed", "CLOSED", 0, 0);
        delivered(condition, "Unconfirmed", "ACTIVE", 40, 0);

        ApiTestClient.Response page = client.send("GET", "/api/v1/me/vacancies?page=1&size=2", null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(titles(page.body())).isEqualTo("Third,Second");
        assertThat(page.body()).contains("\"page\":1,\"size\":2,\"total\":3,\"newCount\":3");
        ApiTestClient.Response next = client.send("GET", "/api/v1/me/vacancies?page=2&size=2", null);
        assertThat(titles(next.body())).isEqualTo("First");
        assertThat(next.body()).contains("\"page\":2,\"size\":2,\"total\":3");
        ApiTestClient.Response beyond = client.send("GET", "/api/v1/me/vacancies?page=3&size=2", null);
        assertThat(titles(beyond.body())).isEmpty();
        assertThat(beyond.body()).contains("\"total\":3");
        ApiTestClient.Response clamped = client.send("GET", "/api/v1/me/vacancies?page=0&size=500", null);
        assertThat(titles(clamped.body())).isEqualTo("Third,Second,First");
        assertThat(clamped.body()).contains("\"page\":1,\"size\":100,\"total\":3");

        ApiTestClient.Response details = client.send("GET", "/api/v1/me/vacancies/" + first, null);
        assertThat(details.status()).isEqualTo(200);
        assertThat(details.body()).contains("\"position\":\"First\"", "\"employer\":\"Employer Ltd\"",
                "\"agency\":null", "\"countries\":[\"SK\"]", "\"format\":null", "\"countryUncertain\":false",
                "\"url\":\"https://example.com/First\"", "\"code\":\"NEW\"", "\"closed\":false")
                .doesNotContain("\"state\"", "lastConfirmedAt");
        assertThat(events(details.body())).isEqualTo("RECEIVED");
        jdbcTemplate.update("DELETE FROM company_source WHERE source_id = (SELECT id FROM source WHERE board = ?)",
                "vacancy-test-second");
        jdbcTemplate.update("UPDATE source SET employer_name = 'Second Group' WHERE board = ?", "vacancy-test-second");
        assertThat(client.send("GET", "/api/v1/me/vacancies/" + second, null).body())
                .contains("\"employer\":\"Second Group\"");

        assertThat(status(client, second, "NOT_INTERESTED")).isEqualTo(204);
        assertThat(status(client, second, "NOT_INTERESTED")).isEqualTo(204);
        assertThat(titles(client.send("GET", "/api/v1/me/vacancies", null).body())).isEqualTo("Third,First");
        assertThat(client.send("GET", "/api/v1/me/unsuitable", null).body())
                .contains("\"position\":\"Second\"", "\"page\":1,\"size\":20,\"total\":1,\"newCount\":0");

        client.ifMatch = client.send("GET", "/api/v1/me/search-condition", null).etag();
        client.send("PUT", "/api/v1/me/search-condition", "{\"countries\":[\"SK\"],\"positions\":[\"devops-engineer\"]}");
        assertThat(titles(client.send("GET", "/api/v1/me/vacancies", null).body())).isEmpty();
        assertThat(titles(client.send("GET", "/api/v1/me/unsuitable", null).body())).isEqualTo("Second");
        assertThat(client.send("GET", "/api/v1/me/vacancies/" + second, null).body())
                .contains("\"code\":\"NOT_INTERESTED\"");

        assertThat(status(client, second, "RETURNED")).isEqualTo(204);
        assertThat(status(client, second, "RETURNED")).isEqualTo(204);
        assertThat(titles(client.send("GET", "/api/v1/me/unsuitable", null).body())).isEmpty();
        String returned = client.send("GET", "/api/v1/me/vacancies/" + second, null).body();
        assertThat(returned).contains("\"code\":\"VIEWED\"");
        assertThat(events(returned)).isEqualTo("RECEIVED,NOT_INTERESTED,RETURNED");

        assertThat(status(client, second, "HIRED")).isEqualTo(400);
        assertThat(client.send("PUT", "/api/v1/me/vacancies/" + second + "/status",
                "{\"status\":\"APPLIED\",\"zone\":\"Mars/Olympus\"}").status()).isEqualTo(400);
    }

    /**
     * Статусы (бизнес-описание §6, §156): «Новая» до открытия сведений и «новых M»; «Резюме отправлено» уводит
     * вакансию в «Мои отклики»; «Собеседование» в тот же день заменяет его в истории; закрытая работодателем вакансия
     * остаётся в «Моих откликах» с пометкой и строкой истории «Закрыта работодателем».
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void statusesMoveVacancyToResponses() throws Exception {
        ApiTestClient client = ApiTestClient.signedIn(port(), "user@example.com");
        client.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        long condition = activeCondition();
        long applied = delivered(condition, "Applied", "ACTIVE", 0, 2);
        delivered(condition, "Untouched", "ACTIVE", 0, 1);

        assertThat(status(client, applied, "VIEWED")).isEqualTo(204);
        assertThat(status(client, applied, "VIEWED")).isEqualTo(204);
        String list = client.send("GET", "/api/v1/me/vacancies", null).body();
        assertThat(list).contains("\"total\":2,\"newCount\":1")
                .containsPattern("\"position\":\"Applied\".*?\"code\":\"VIEWED\"")
                .containsPattern("\"position\":\"Untouched\".*?\"code\":\"NEW\"");

        assertThat(status(client, applied, "APPLIED")).isEqualTo(204);
        assertThat(status(client, applied, "INTERVIEW")).isEqualTo(204);
        assertThat(titles(client.send("GET", "/api/v1/me/vacancies", null).body())).isEqualTo("Untouched");
        String responses = client.send("GET", "/api/v1/me/responses", null).body();
        assertThat(titles(responses)).isEqualTo("Applied");
        assertThat(responses).contains("\"code\":\"INTERVIEW\"", "\"closed\":false", "\"newCount\":0");
        assertThat(events(client.send("GET", "/api/v1/me/vacancies/" + applied, null).body()))
                .isEqualTo("RECEIVED,VIEWED,INTERVIEW");

        // Минута вперёд — закрытие позже статуса, поставленного по часам JVM (часы БД могут немного отличаться).
        jdbcTemplate.update("UPDATE vacancy SET state = 'CLOSED', closed_at = now() + interval '1 minute' WHERE id = ?",
                applied);
        assertThat(client.send("GET", "/api/v1/me/responses", null).body()).contains("\"closed\":true");
        assertThat(events(client.send("GET", "/api/v1/me/vacancies/" + applied, null).body()))
                .isEqualTo("RECEIVED,VIEWED,INTERVIEW,CLOSED");
    }

    /**
     * Сценарий 14 (список): вакансия первого пользователя второму недоступна — сведения и статус {@code 404};
     * без условий список пуст, отмеченных и откликов нет.
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
        status(owner, vacancy, "NOT_INTERESTED");

        ApiTestClient other = ApiTestClient.signedIn(port(), "other@example.com");
        assertThat(other.send("GET", "/api/v1/me/vacancies", null).body()).isEqualTo(EMPTY_PAGE);
        assertThat(other.send("GET", "/api/v1/me/vacancies/" + vacancy, null).status()).isEqualTo(404);
        assertThat(status(other, vacancy, "NOT_INTERESTED")).isEqualTo(404);
        assertThat(other.send("GET", "/api/v1/me/unsuitable", null).body()).isEqualTo(EMPTY_PAGE);
        assertThat(other.send("GET", "/api/v1/me/responses", null).body()).isEqualTo(EMPTY_PAGE);
        assertThat(new ApiTestClient(port()).send("GET", "/api/v1/me/vacancies", null).status()).isEqualTo(401);
    }

    /**
     * Видимость по стране (решения владельца 2026-10-20, §148): выданная вакансия видна, если среди её определённых
     * стран есть страна условий или страна не определена (пустой список); другая страна, только «*» (удалённо «весь
     * мир») и не рассчитанные страны ({@code NULL}) — не видны. Пометка «страна не определена» — только у вакансии без
     * стран (аудит §154).
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void listShowsOnlyCountryOfConditions() throws Exception {
        ApiTestClient client = ApiTestClient.signedIn(port(), "user@example.com");
        client.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        long condition = activeCondition();
        delivered(condition, "Slovakia", "ACTIVE", 0, 5);
        countries(delivered(condition, "Czechia", "ACTIVE", 0, 4), "{CZ}");
        countries(delivered(condition, "Worldwide", "ACTIVE", 0, 3), "{*}");
        countries(delivered(condition, "NoCountry", "ACTIVE", 0, 2), "{}");
        countries(delivered(condition, "NotResolved", "ACTIVE", 0, 1), null);

        String body = client.send("GET", "/api/v1/me/vacancies", null).body();
        assertThat(titles(body)).isEqualTo("NoCountry,Slovakia");
        assertThat(body).containsPattern("\"position\":\"NoCountry\".*?\"countryUncertain\":true")
                .containsPattern("\"position\":\"Slovakia\".*?\"countryUncertain\":false");
    }

    /**
     * Условия «офис» (решение владельца 2026-10-16, §124): видны офисная и гибридная вакансии, удалённая — нет.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void officeListShowsHybrid() throws Exception {
        ApiTestClient client = ApiTestClient.signedIn(port(), "user@example.com");
        client.send("PUT", "/api/v1/me/search-condition",
                "{\"countries\":[\"SK\"],\"positions\":[\"java-developer\"],\"format\":\"OFFICE\"}");
        long condition = activeCondition();
        format(delivered(condition, "Office", "ACTIVE", 0, 3), "OFFICE");
        format(delivered(condition, "Hybrid", "ACTIVE", 0, 2), "HYBRID");
        format(delivered(condition, "Remote", "ACTIVE", 0, 1), "REMOTE");

        assertThat(titles(client.send("GET", "/api/v1/me/vacancies", null).body())).isEqualTo("Hybrid,Office");
    }

    /**
     * Порядок и дата открытия (решение владельца 2026-10-16, §127): дата размещения работодателем, без неё — дата
     * первого обнаружения («найдена», 60 дней назад); открытые позже — сверху, независимо от порядка выдачи.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void listIsOrderedByOpeningDate() throws Exception {
        ApiTestClient client = ApiTestClient.signedIn(port(), "user@example.com");
        client.send("PUT", "/api/v1/me/search-condition", JAVA_SK);
        long condition = activeCondition();
        posted(delivered(condition, "PostedLongAgo", "ACTIVE", 0, 1), 90);
        delivered(condition, "FoundOnly", "ACTIVE", 0, 2);
        posted(delivered(condition, "PostedYesterday", "ACTIVE", 0, 3), 1);

        String body = client.send("GET", "/api/v1/me/vacancies", null).body();

        assertThat(titles(body)).isEqualTo("PostedYesterday,FoundOnly,PostedLongAgo");
        assertThat(body).contains("\"byEmployer\":true", "\"byEmployer\":false", "\"opened\":{\"at\":");
    }

    private static int status(ApiTestClient client, long vacancy, String status) throws Exception {
        return client.send("PUT", "/api/v1/me/vacancies/" + vacancy + "/status",
                "{\"status\":\"" + status + "\",\"zone\":\"Europe/Bratislava\"}").status();
    }

    private void posted(long vacancy, int daysAgo) {
        jdbcTemplate.update("UPDATE vacancy SET posted_at = date_trunc('day', now() - make_interval(days => ?)) "
                + "WHERE id = ?", daysAgo, vacancy);
    }

    private void format(long vacancy, String format) {
        jdbcTemplate.update("UPDATE vacancy SET work_format = ? WHERE id = ?", format, vacancy);
    }

    private void countries(long vacancy, String countries) {
        jdbcTemplate.update("UPDATE vacancy SET work_countries = ?::text[] WHERE id = ?", countries, vacancy);
    }

    /**
     * Вакансия с публикацией в источнике компании-работодателя, подходящая позиции «Java developer», выданная в
     * список версии условий.
     *
     * @param daysUnconfirmed сколько дней не подтверждалась
     * @param hoursAgo        сколько часов назад выдана; порядок списка от времени выдачи не зависит (§127): дата
     *                        открытия — момент вставки вакансии минус 60 дней, так что и по дате, и по id созданная
     *                        позже выше
     */
    private long delivered(long condition, String title, String state, int daysUnconfirmed, int hoursAgo) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, work_countries)
                VALUES (?, ?, ?, now() - interval '60 days', now() - make_interval(days => ?), ARRAY['SK'])
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
        return joined(body, "position");
    }

    private static String events(String body) {
        return joined(body, "event");
    }

    private static String joined(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        StringBuilder values = new StringBuilder();
        while (matcher.find()) {
            values.append(values.isEmpty() ? "" : ",").append(matcher.group(1));
        }
        return values.toString();
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
