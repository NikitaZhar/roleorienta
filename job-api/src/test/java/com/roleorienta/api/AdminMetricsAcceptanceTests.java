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
 * функций; состояние ядра сейчас с тревогами «ядро остановилось» (§142).
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
        for (String table : new String[] {"delivered_vacancy", "pass_run",
                "vacancy_status_history", "vacancy_user_status",
                "search_condition",
                "digest_vacancy", "user_account", "spring_session_attributes", "spring_session", "core_metric_daily"}) {
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

    /**
     * Состояние ядра: последнее успешное чтение 3 ч назад и задание 3 ч в очереди — обе тревоги; свежее чтение и
     * задание, созданное сутки назад, но только что вернувшееся в очередь (повтор), — тревог нет, чтения и отказы за час
     * посчитаны (аудит §143).
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void coreStatusWarnsWhenCoreStops() throws Exception {
        jdbcTemplate.update("DELETE FROM crawl_run");
        jdbcTemplate.update("DELETE FROM task");
        long source = jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES ('greenhouse', "
                + "'qwstatus') ON CONFLICT (provider, board) DO UPDATE SET board = EXCLUDED.board RETURNING id",
                Long.class);
        jdbcTemplate.update("INSERT INTO crawl_run (source_id, started_at, finished_at, state, postings_count) "
                + "VALUES (?, now() - interval '3 hours', now() - interval '3 hours', 'COMPLETE', 1)", source);
        jdbcTemplate.update("INSERT INTO task (type, task_key, payload, state, created_at, updated_at) "
                + "VALUES ('READ_SOURCE', 'qwstatus', '{}', 'QUEUED', now() - interval '3 hours', "
                + "now() - interval '3 hours')");
        ApiTestClient client = new ApiTestClient(port());
        assertThat(client.send("POST", "/api/v1/admin/login", admin(ADMIN_PASSWORD)).status()).isEqualTo(200);

        assertThat(client.send("GET", "/api/v1/admin/status", null).body())
                .contains("\"lastHour\":0", "\"queued\":1", "\"warnings\":[\"NO_RECENT_READ\",\"QUEUE_STALLED\"]");

        jdbcTemplate.update("UPDATE task SET created_at = now() - interval '1 day', updated_at = now()");
        jdbcTemplate.update("INSERT INTO crawl_run (source_id, started_at, finished_at, state, postings_count) "
                + "VALUES (?, now(), now(), 'COMPLETE', 1)", source);
        jdbcTemplate.update("INSERT INTO crawl_run (source_id, started_at, finished_at, state, failure_kind, "
                + "postings_count) VALUES (?, now(), now(), 'FAILED', 'TEMPORARY', 0)", source);

        assertThat(client.send("GET", "/api/v1/admin/status", null).body()).contains("\"lastHour\":1",
                "\"failedLastHour\":1", "\"queued\":1", "\"warnings\":[]");
    }

    /**
     * Раздел Companies (§206): источник без компании — в списке с числом открытых публикаций и названиями; закрытие —
     * публикации закрыты, вакансия без других публикаций закрыта, с открытой публикацией другого источника — нет;
     * повторное закрытие — {@code 404}; кадровые страницы, добавленные пользователями, — в списке администратора.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void administratorClosesSourceWithoutCompanyAndSeesRequests() throws Exception {
        jdbcTemplate.update("DELETE FROM job_posting WHERE source_id IN (SELECT id FROM source WHERE board LIKE 'qw-src-%')");
        jdbcTemplate.update("DELETE FROM vacancy WHERE primary_url LIKE 'https://qw-src.example/%'");
        jdbcTemplate.update("DELETE FROM source WHERE board LIKE 'qw-src-%'");
        jdbcTemplate.update("DELETE FROM company WHERE registration_number = '99900077'");
        long blog = jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES ('careerpage', "
                + "'qw-src-blog') RETURNING id", Long.class);
        long other = jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES ('greenhouse', "
                + "'qw-src-other') RETURNING id", Long.class);
        long onlyBlog = vacancyWithPosting(blog, "Our new blog post");
        long shared = vacancyWithPosting(blog, "Java developer");
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at)
                VALUES (?, ?, 'qw-other', 'Java developer', 'https://qw-src.example/other', now(), now())""",
                other, shared);
        ApiTestClient client = new ApiTestClient(port());
        assertThat(client.send("POST", "/api/v1/admin/login", admin(ADMIN_PASSWORD)).status()).isEqualTo(200);

        // Доска системы найма без компании — настоящие вакансии, в списке её нет.
        assertThat(client.send("GET", "/api/v1/admin/sources/unlinked", null).body()).doesNotContain("qw-src-other");
        assertThat(client.send("GET", "/api/v1/admin/sources/unlinked", null).body()).contains("\"id\":" + blog
                + ",\"provider\":\"careerpage\",\"board\":\"qw-src-blog\",\"openPostings\":2,\"titles\":[\"Our new blog post\","
                + "\"Java developer\"]");
        assertThat(client.send("POST", "/api/v1/admin/sources/" + blog + "/close", null).status()).isEqualTo(204);
        assertThat(client.send("POST", "/api/v1/admin/sources/" + blog + "/close", null).status()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM vacancy WHERE id = ?", String.class, onlyBlog))
                .isEqualTo("CLOSED");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM vacancy WHERE id = ?", String.class, shared))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM job_posting WHERE source_id = ? "
                + "AND closed_at IS NULL", Integer.class, blog)).isZero();
        assertThat(client.send("GET", "/api/v1/admin/sources/unlinked", null).body()).doesNotContain("qw-src-blog");

        long user = jdbcTemplate.queryForObject("INSERT INTO user_account (email, password_hash) VALUES "
                + "('qw@example.com', '-') RETURNING id", Long.class);
        long company = jdbcTemplate.queryForObject("INSERT INTO company (country, registration_number, name, registry) "
                + "VALUES ('SK', '99900077', 'Request Ltd', 'RPO') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO company_request (user_id, company_id, career_url) VALUES (?, ?, "
                + "'https://request.example/careers')", user, company);
        assertThat(client.send("GET", "/api/v1/admin/career-requests", null).body()).contains(
                "\"companyName\":\"Request Ltd\",\"url\":\"https://request.example/careers\",\"status\":{\"state\":\"PENDING\"");

        // Адрес от администратора (§214): та же проверка, без пользователя; повтор того же адреса — 409.
        String address = "{\"url\":\"https://request.example/jobs\"}";
        assertThat(client.send("POST", "/api/v1/admin/companies/" + company + "/career-page", address).status())
                .isEqualTo(201);
        assertThat(client.send("POST", "/api/v1/admin/companies/" + company + "/career-page", address).status())
                .isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_request WHERE user_id IS NULL "
                + "AND career_url = 'https://request.example/jobs'", Integer.class)).isEqualTo(1);

        // Выбрано в условиях — вакансий нет (§214): группа без единой открытой вакансии.
        long condition = jdbcTemplate.queryForObject("INSERT INTO search_condition (user_id, countries, portion_limit, "
                + "active) VALUES (?, '{SK}', 20, TRUE) RETURNING id", Long.class, user);
        jdbcTemplate.update("INSERT INTO search_condition_occupation (search_condition_id, code) VALUES (?, '0110')",
                condition);
        assertThat(client.send("GET", "/api/v1/admin/positions/without-vacancies", null).body())
                .contains("{\"code\":\"0110\",\"name\":\"Commissioned armed forces officers\",\"conditions\":1}");
    }

    /**
     * Потери по одной компании (§235, §239): в очереди — только то, что может решить администратор, после проверки всех
     * сайтов компании («формат не поддержан» и непроверенный сайт — нет); больше потерянных — первой; в карточке — сайт и вакансии портала, которых нет на
     * собственной странице («Java Developer (m/ž)» портала найдена как «Java developer»); «Later» — следующая
     * компания; «No own career page» — потерь нет ({@code 204}), снятие отметки — компания снова в очереди.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void administratorHandlesLossesOneByOne() throws Exception {
        jdbcTemplate.update("DELETE FROM job_posting WHERE source_id IN (SELECT id FROM source WHERE board LIKE 'qw-loss-%')");
        jdbcTemplate.update("DELETE FROM vacancy WHERE primary_url LIKE 'https://qw-loss.example/%'");
        jdbcTemplate.update("DELETE FROM company_source WHERE source_id IN (SELECT id FROM source WHERE board LIKE 'qw-loss-%')");
        jdbcTemplate.update("DELETE FROM source WHERE board LIKE 'qw-loss-%'");
        jdbcTemplate.update("DELETE FROM company_site WHERE host LIKE '%loss.example'");
        jdbcTemplate.update("DELETE FROM company_coverage");
        jdbcTemplate.update("DELETE FROM company WHERE registration_number IN ('99900081', '99900082', '99900083', "
                + "'99900084', '99900085')");
        long big = lossCompany("99900081", "Big Loss Ltd", "5, 1, 1, 'NO_CAREER_PAGE'");
        long small = lossCompany("99900082", "Small Loss Ltd", "2, 0, 0, 'NO_CAREER_PAGE'");
        // Работа ядра, не администратора (§239): страница найдена, формат не поддержан; сайт ещё не проверен.
        lossCompany("99900083", "Unsupported Format Ltd", "9, 0, 0, 'FORMAT_UNSUPPORTED'");
        long unchecked = lossCompany("99900084", "Unchecked Site Ltd", "9, 0, 0, 'NO_CAREER_PAGE'");
        // Последний добавленный адрес не подключён — работа ядра, администратору по кругу не возвращается (§243).
        long failed = lossCompany("99900085", "Failed Address Ltd", "20, 0, 0, 'UNREACHABLE'");
        jdbcTemplate.update("INSERT INTO company_request (company_id, career_url, state, reason, created_at) VALUES "
                + "(?, 'https://failed.loss.example/', 'NOT_CONNECTED', 'UNREACHABLE', now() - interval '1 day')", failed);
        jdbcTemplate.update("INSERT INTO company_request (company_id, career_url, state, created_at) VALUES "
                + "(?, 'https://loss.example/jobs', 'CONNECTED', now() - interval '1 day')", big);
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, status, proof, check_result, career_url,
                                          checked_at)
                VALUES (?, 'loss.example', 'https://loss.example/', 'STATE_PORTAL', 'FOUND', 'REGISTRATION_NUMBER',
                        'NO_CAREER_PAGE', 'https://loss.example/kariera', now())""", big);
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, status, proof)
                VALUES (?, 'unchecked.loss.example', 'https://unchecked.loss.example/', 'STATE_PORTAL', 'FOUND',
                        'REGISTRATION_NUMBER')""", unchecked);
        // Второй сайт из Common Crawl ядро не проверяет (§80): его «не проверен» компанию из очереди не убирает (§250).
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, status, proof, check_result, checked_at)
                VALUES (?, 'small.loss.example', 'https://small.loss.example/', 'STATE_PORTAL', 'FOUND',
                        'REGISTRATION_NUMBER', 'NO_CAREER_PAGE', now())""", small);
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, status, proof)
                VALUES (?, 'cc.small.loss.example', 'https://cc.small.loss.example/', 'COMMON_CRAWL', 'FOUND',
                        'REGISTRATION_NUMBER')""", small);
        long portal = lossSource("sluzbyzamestnanosti", "qw-loss-portal", big);
        long own = lossSource("careerpage", "qw-loss-own", big);
        lossPosting(portal, "Java Developer (m/ž)");
        lossPosting(portal, "Skladník");
        lossPosting(own, "Java developer");
        ApiTestClient client = new ApiTestClient(port());
        assertThat(client.send("POST", "/api/v1/admin/login", admin(ADMIN_PASSWORD)).status()).isEqualTo(200);

        String first = client.send("GET", "/api/v1/admin/losses/next", null).body();
        assertThat(first).contains("\"id\":" + big + ",\"registrationNumber\":\"99900081\",\"name\":\"Big Loss Ltd\","
                + "\"counts\":{\"portalVacancies\":5,\"foundOnOwn\":1,\"ownVacancies\":1,\"channel\":\"NO_CAREER_PAGE\"},"
                + "\"remaining\":2}");
        assertThat(first).contains("{\"host\":\"loss.example\",\"checkResult\":\"NO_CAREER_PAGE\","
                + "\"careerUrl\":\"https://loss.example/kariera\"}");
        assertThat(first).contains("\"missing\":[{\"title\":\"Skladník\"");
        assertThat(first).contains("\"lastAddress\":{\"url\":\"https://loss.example/jobs\",\"state\":\"CONNECTED\","
                + "\"reason\":null}");
        assertThat(first).doesNotContain("Java Developer");

        assertThat(client.send("POST", "/api/v1/admin/companies/" + big + "/loss-postponed", null).status())
                .isEqualTo(204);
        assertThat(client.send("GET", "/api/v1/admin/losses/next", null).body())
                .contains("\"name\":\"Small Loss Ltd\"").contains("\"remaining\":1");
        assertThat(client.send("PUT", "/api/v1/admin/companies/" + small + "/no-own-career-page", null).status())
                .isEqualTo(204);
        assertThat(client.send("GET", "/api/v1/admin/losses/next", null).status()).isEqualTo(204);
        assertThat(client.send("DELETE", "/api/v1/admin/companies/" + small + "/no-own-career-page", null).status())
                .isEqualTo(204);
        assertThat(client.send("GET", "/api/v1/admin/losses/next", null).body()).contains("\"name\":\"Small Loss Ltd\"");
        // Адрес кадровой страницы снимает отметку «своей страницы нет» (§244).
        assertThat(client.send("PUT", "/api/v1/admin/companies/" + small + "/no-own-career-page", null).status())
                .isEqualTo(204);
        assertThat(client.send("POST", "/api/v1/admin/companies/" + small + "/career-page",
                "{\"url\":\"https://small.loss.example/kariera\"}").status()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("SELECT no_own_career_page FROM company WHERE id = ?", Boolean.class,
                small)).isFalse();
        assertThat(client.send("POST", "/api/v1/admin/companies/0/loss-postponed", null).status()).isEqualTo(404);
    }

    private long lossCompany(String number, String name, String coverage) {
        long company = jdbcTemplate.queryForObject("INSERT INTO company (country, registration_number, name, registry) "
                + "VALUES ('SK', ?, ?, 'RPO') RETURNING id", Long.class, number, name);
        jdbcTemplate.update("INSERT INTO company_coverage (company_id, portal_titles, found_on_own, own_vacancies, "
                + "channel) VALUES (?, " + coverage + ")", company);
        return company;
    }

    private long lossSource(String provider, String board, long company) {
        long source = jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES (?, ?) RETURNING id",
                Long.class, provider, board);
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id, role) VALUES (?, ?, 'EMPLOYER')",
                company, source);
        return source;
    }

    private void lossPosting(long source, String title) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', ?, ?, now(), now()) RETURNING id""", Long.class, title,
                "https://qw-loss.example/" + source + "/" + title.hashCode());
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at)
                VALUES (?, ?, ?, ?, ?, now(), now())""", source, vacancy, "qw-loss-" + vacancy, title,
                "https://qw-loss.example/" + vacancy);
    }

    private long vacancyWithPosting(long source, String title) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', ?, ?, now(), now()) RETURNING id""", Long.class, title,
                "https://qw-src.example/" + title.hashCode());
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at)
                VALUES (?, ?, ?, ?, ?, now(), now())""", source, vacancy, "qw-" + vacancy, title,
                "https://qw-src.example/" + vacancy);
        return vacancy;
    }

    private static String admin(String password) {
        return "{\"username\":\"admin\",\"password\":\"" + password + "\"}";
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
