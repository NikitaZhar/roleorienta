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
 * Группы компаний, сомнительные компании и адрес кадровой страницы, введённый пользователем (§137; бизнес-описание
 * §4.2, §5, сценарий 12 — ввод и состояние запроса; проверку адреса делает job-worker), личное «скрыть» и отметка
 * администратора «не работодатель» (§138) через настоящий HTTP. Компании теста — с номерами {@code 9901xxxx};
 * ограничение частоты в тесте — 2 адреса в сутки.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.company-requests.daily-limit=2", "app.admin.password=" + CompanyAcceptanceTests.ADMIN})
@Import(TestcontainersConfiguration.class)
class CompanyAcceptanceTests {

    /** Пароль администратора в тесте. */
    static final String ADMIN = "admin-secret-2";

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long alfa;
    private long beta;
    private long gama;
    private long delta;
    private long epsi;

    /**
     * Qwalfa — подключена своей системой найма (YES); Qwbeta — сайт найден, страница не читается (MAYBE); Qwgama — сайта
     * нет, мелкая (NO, только поиском); Qwdelta — сайта нет, 50+ сотрудников (NO, в сомнительных); Qwepsi — подключена
     * только порталом (PORTAL, §143).
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM company_request");
        jdbcTemplate.update("DELETE FROM company_source WHERE company_id IN "
                + "(SELECT id FROM company WHERE registration_number LIKE '9901%')");
        jdbcTemplate.update("DELETE FROM company_site WHERE company_id IN "
                + "(SELECT id FROM company WHERE registration_number LIKE '9901%')");
        jdbcTemplate.update("DELETE FROM company_check WHERE company_id IN "
                + "(SELECT id FROM company WHERE registration_number LIKE '9901%')");
        jdbcTemplate.update("DELETE FROM company WHERE registration_number LIKE '9901%'");
        for (String table : new String[] {"delivered_vacancy", "pass_run", "vacancy_status_history", "vacancy_user_status",
                "search_condition",
                "digest_vacancy", "user_account", "spring_session_attributes", "spring_session"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        alfa = company("99010001", "Qwalfa s.r.o.", null);
        beta = company("99010002", "Qwbeta a.s.", null);
        gama = company("99010003", "Qwgama s.r.o.", null);
        delta = company("99010004", "Qwdelta s.r.o.", 50);
        jdbcTemplate.update("INSERT INTO company_check (company_id, result, first_checked_at, checked_at) "
                + "VALUES (?, 'CONNECTED', now(), now()), (?, 'FORMAT_UNSUPPORTED', now(), now()), "
                + "(?, 'SITE_NOT_FOUND', now(), now()), (?, 'SITE_NOT_FOUND', now(), now())", alfa, beta, gama, delta);
        jdbcTemplate.update("INSERT INTO company_site (company_id, host, evidence_url, source, status, proof, "
                + "career_url) VALUES (?, 'qwbeta.sk', 'https://qwbeta.sk/', 'NAME', 'FOUND', 'BRAND', "
                + "'https://qwbeta.sk/kariera')", beta);
        epsi = company("99010006", "Qwepsi s.r.o.", 20);
        jdbcTemplate.update("INSERT INTO company_check (company_id, result, first_checked_at, checked_at) "
                + "VALUES (?, 'CONNECTED', now(), now())", epsi);
        link(alfa, "greenhouse", "qwalfa");
        link(epsi, "sluzbyzamestnanosti", "99010006");
    }

    private void link(long company, String provider, String board) {
        long source = jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES (?, ?) "
                + "ON CONFLICT (provider, board) DO UPDATE SET board = EXCLUDED.board RETURNING id", Long.class,
                provider, board);
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id) VALUES (?, ?)", company, source);
    }

    /**
     * Поиск по части названия без диакритики и регистра и по IČO; группа, причина, сайт и кадровая страница; запрос
     * короче 3 символов — {@code 400}; без входа — {@code 401}.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void searchShowsGroupReasonSiteAndCareerPage() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");

        String found = user.send("GET", "/api/v1/companies?query=QWBETA", null).body();
        assertThat(found).contains("\"id\":" + beta, "\"group\":\"MAYBE\"", "\"reason\":\"FORMAT_UNSUPPORTED\"",
                "\"site\":\"qwbeta.sk\"", "\"careerUrl\":\"https://qwbeta.sk/kariera\"");
        assertThat(user.send("GET", "/api/v1/companies?query=99010001", null).body())
                .contains("\"id\":" + alfa, "\"group\":\"YES\"");
        assertThat(user.send("GET", "/api/v1/companies?query=qw", null).status()).isEqualTo(400);
        assertThat(new ApiTestClient(port()).send("GET", "/api/v1/companies?query=qwbeta", null).status())
                .isEqualTo(401);
    }

    /**
     * Сомнительные: «может быть» и «нет» с признаком найма; подключённая и мелкая без сайта — нет; страницы по номеру
     * с общим числом (§163).
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void doubtfulListsMaybeAndHiringCompaniesWithoutSite() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");

        String list = user.send("GET", "/api/v1/companies/doubtful?size=100", null).body();

        assertThat(list).contains("\"id\":" + beta + ",", "\"id\":" + delta + ",")
                .doesNotContain("\"id\":" + alfa + ",", "\"id\":" + gama + ",");
        long total = Long.parseLong(list.replaceAll(".*\"total\":(\\d+).*", "$1"));
        assertThat(total).isGreaterThanOrEqualTo(2);
        String first = user.send("GET", "/api/v1/companies/doubtful?page=1&size=1", null).body();
        String second = user.send("GET", "/api/v1/companies/doubtful?page=2&size=1", null).body();
        assertThat(first).contains("\"page\":1,\"size\":1,\"total\":" + total);
        assertThat(second).contains("\"page\":2,\"size\":1,\"total\":" + total).isNotEqualTo(first);
    }

    /**
     * Ввод адреса: подключённая — {@code 409}, не http(s) — {@code 400}, принят — {@code 201} «в обработке»; запросы
     * видит только их автор; третий адрес за сутки — {@code 429}.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void userAddsCareerPageWithinDailyLimit() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        String page = "{\"url\":\"https://qwgama.sk/kariera\"}";

        assertThat(user.send("POST", "/api/v1/companies/" + alfa + "/career-page", page).status()).isEqualTo(409);
        assertThat(user.send("POST", "/api/v1/companies/" + gama + "/career-page", "{\"url\":\"ftp://qwgama.sk\"}")
                .status()).isEqualTo(400);
        ApiTestClient.Response added = user.send("POST", "/api/v1/companies/" + gama + "/career-page", page);
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.body()).contains("\"companyName\":\"Qwgama s.r.o.\"", "\"state\":\"PENDING\"");
        assertThat(user.send("POST", "/api/v1/companies/" + beta + "/career-page",
                "{\"url\":\"https://qwbeta.sk/praca\"}").status()).isEqualTo(201);
        assertThat(user.send("POST", "/api/v1/companies/" + delta + "/career-page",
                "{\"url\":\"https://qwdelta.sk/praca\"}").status()).isEqualTo(429);

        assertThat(user.send("GET", "/api/v1/me/career-requests", null).body())
                .contains("https://qwgama.sk/kariera", "https://qwbeta.sk/praca");
        ApiTestClient other = ApiTestClient.signedIn(port(), "other@example.com");
        assertThat(other.send("GET", "/api/v1/me/career-requests", null).body()).isEqualTo("[]");
    }

    /**
     * В поиске сначала компании с читаемой кадровой страницей, затем по названию.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void searchListsConnectedCompaniesFirst() throws Exception {
        long holding = company("99010005", "Aaqwalfa holding s.r.o.", null);
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");

        String found = user.send("GET", "/api/v1/companies?query=qwalfa", null).body();

        assertThat(found.indexOf("\"id\":" + alfa + ",")).isNotNegative()
                .isLessThan(found.indexOf("\"id\":" + holding + ","));
    }

    /**
     * Скрытая компания пропадает из поиска и сомнительных только у скрывшего, видна в его списке скрытых и
     * возвращается; неизвестная компания — {@code 404}.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void hiddenCompanyDisappearsOnlyForThatUser() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        ApiTestClient other = ApiTestClient.signedIn(port(), "other@example.com");

        assertThat(user.send("PUT", "/api/v1/me/hidden-companies/" + beta, null).status()).isEqualTo(204);
        assertThat(user.send("PUT", "/api/v1/me/hidden-companies/" + beta, null).status()).isEqualTo(204);
        assertThat(user.send("PUT", "/api/v1/me/hidden-companies/-1", null).status()).isEqualTo(404);

        assertThat(user.send("GET", "/api/v1/companies?query=qwbeta", null).body()).isEqualTo("[]");
        assertThat(user.send("GET", "/api/v1/companies/doubtful?size=100", null).body())
                .doesNotContain("\"id\":" + beta + ",");
        assertThat(user.send("GET", "/api/v1/me/hidden-companies", null).body()).contains("\"id\":" + beta + ",");
        assertThat(other.send("GET", "/api/v1/companies?query=qwbeta", null).body()).contains("\"id\":" + beta + ",");
        assertThat(other.send("GET", "/api/v1/companies/doubtful?size=100", null).body())
                .contains("\"id\":" + beta + ",");

        assertThat(user.send("DELETE", "/api/v1/me/hidden-companies/" + beta, null).status()).isEqualTo(204);
        assertThat(user.send("GET", "/api/v1/companies?query=qwbeta", null).body()).contains("\"id\":" + beta + ",");
        assertThat(user.send("GET", "/api/v1/me/hidden-companies", null).body()).isEqualTo("[]");
    }

    /**
     * Отметка «не работодатель» — только администратор; компания получает группу NOT_EMPLOYER у всех, уходит из
     * сомнительных, ввод адреса — {@code 409}; снятие отметки возвращает её.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void administratorMarksCompanyAsNotEmployer() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        assertThat(user.send("PUT", "/api/v1/admin/companies/" + delta + "/not-employer", null).status())
                .isEqualTo(403);
        ApiTestClient admin = new ApiTestClient(port());
        assertThat(admin.send("POST", "/api/v1/admin/login",
                "{\"username\":\"admin\",\"password\":\"" + ADMIN + "\"}").status()).isEqualTo(200);

        assertThat(admin.send("PUT", "/api/v1/admin/companies/" + delta + "/not-employer", null).status())
                .isEqualTo(204);
        assertThat(admin.send("PUT", "/api/v1/admin/companies/-1/not-employer", null).status()).isEqualTo(404);

        assertThat(admin.send("GET", "/api/v1/admin/companies?query=qwdelta", null).body())
                .contains("\"group\":\"NOT_EMPLOYER\"");
        assertThat(user.send("GET", "/api/v1/companies?query=qwdelta", null).body())
                .contains("\"group\":\"NOT_EMPLOYER\"", "\"reason\":null");
        assertThat(user.send("GET", "/api/v1/companies/doubtful?size=100", null).body())
                .doesNotContain("\"id\":" + delta + ",");
        assertThat(user.send("POST", "/api/v1/companies/" + delta + "/career-page",
                "{\"url\":\"https://qwdelta.sk/praca\"}").status()).isEqualTo(409);

        assertThat(admin.send("DELETE", "/api/v1/admin/companies/" + delta + "/not-employer", null).status())
                .isEqualTo(204);
        assertThat(user.send("GET", "/api/v1/companies/doubtful?size=100", null).body())
                .contains("\"id\":" + delta + ",");
    }

    /**
     * «Только портал» (§143): своя группа, нет в сомнительных, адрес кадровой страницы принимается; тот же адрес, пока
     * проверяется, — {@code 409}; поиск администратора не учитывает личные скрытия.
     *
     * @throws Exception ошибка HTTP
     */
    @Test
    void portalOnlyCompanyAcceptsCareerPage() throws Exception {
        ApiTestClient user = ApiTestClient.signedIn(port(), "user@example.com");
        String page = "{\"url\":\"https://qwepsi.sk/kariera\"}";

        assertThat(user.send("GET", "/api/v1/companies?query=qwepsi", null).body())
                .contains("\"group\":\"PORTAL\"", "\"reason\":null");
        assertThat(user.send("GET", "/api/v1/companies/doubtful?size=100", null).body())
                .doesNotContain("\"id\":" + epsi + ",");
        assertThat(user.send("POST", "/api/v1/companies/" + epsi + "/career-page", page).status()).isEqualTo(201);
        assertThat(user.send("POST", "/api/v1/companies/" + epsi + "/career-page", page).status()).isEqualTo(409);

        assertThat(user.send("PUT", "/api/v1/me/hidden-companies/" + epsi, null).status()).isEqualTo(204);
        ApiTestClient admin = new ApiTestClient(port());
        assertThat(admin.send("POST", "/api/v1/admin/login",
                "{\"username\":\"admin\",\"password\":\"" + ADMIN + "\"}").status()).isEqualTo(200);
        assertThat(admin.send("GET", "/api/v1/admin/companies?query=qwepsi", null).body())
                .contains("\"id\":" + epsi + ",");
    }

    private long company(String number, String name, Integer employees) {
        return jdbcTemplate.queryForObject("INSERT INTO company (country, registration_number, name, registry, "
                + "employees_min) VALUES ('SK', ?, ?, 'RPO', ?) RETURNING id", Long.class, number, name, employees);
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
