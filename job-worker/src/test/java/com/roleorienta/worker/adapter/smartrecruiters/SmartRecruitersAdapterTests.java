package com.roleorienta.worker.adapter.smartrecruiters;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.http.TestHttpClients;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер SmartRecruiters на заглушке: список страницами до первой без новых вакансий, место — из
 * заголовка группы, фильтр страны, текст и место со страницы вакансии (микроразметка
 * {@code JobPosting}), отказ, чужая доска.
 */
class SmartRecruitersAdapterTests {

    private static final String BOARD = "acme";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();

    /** Страницы списка по номеру; нет номера — пустая страница. */
    private final Map<String, String> pages = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();

    private HttpServer server;
    private SmartRecruitersAdapter adapter;

    /**
     * Заглушка: {@code /careers/acme?search=&page=N} — список, {@code /jobs/acme/<id>} — вакансия.
     *
     * @throws IOException порт не открылся
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/careers/" + BOARD, exchange -> {
            String page = exchange.getRequestURI().getQuery().replaceAll(".*page=(\\d+).*", "$1");
            respond(exchange, statuses.getOrDefault(page, 200), pages.getOrDefault(page, list()));
        });
        server.createContext("/jobs/" + BOARD + "/744000000001", exchange -> respond(exchange, 200, """
                <html><head><meta name="robots" content="noindex,nofollow"></head><body>
                <main itemscope itemtype="http://schema.org/JobPosting">
                <h1 itemprop="title">Java Developer</h1>
                <li itemprop="jobLocation" itemscope itemtype="http://schema.org/Place">
                <span itemprop="address" itemscope itemtype="http://schema.org/PostalAddress">
                <meta itemprop="addressCountry" content="Slovakia (Slovak Republic)">
                <meta itemprop="addressLocality" content="Košice"></span></li>
                <div itemprop="description"><p>Java, Spring Boot</p></div>
                </main></body></html>
                """));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new SmartRecruitersAdapter(httpClient,
                new SmartRecruitersProperties(base + "/careers", base + "/jobs", 10));
    }

    /**
     * Остановка заглушки и клиента.
     *
     * @throws IOException ошибка закрытия клиента
     */
    @AfterEach
    void stop() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    /**
     * Две страницы с вакансиями, третья — без новых: чтение полное; место — из заголовка группы.
     */
    @Test
    void readsPagesUntilNoNewPostings() {
        pages.put("0", list(group("Košice, Slovakia (Slovak Republic)", job(744000000001L, "Java Developer"),
                job(744000000002L, "QA Engineer")), group("Prague, Czechia", job(744000000003L, "DevOps Engineer"))));
        pages.put("1", list(group("Bratislava, Slovakia (Slovak Republic)", job(744000000004L, "Data Engineer"))));
        pages.put("2", pages.get("1"));

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(BOARD);

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).extracting(FetchedPosting::externalId)
                .containsExactly("744000000001", "744000000002", "744000000003", "744000000004");
        assertThat(read.postings().get(0)).isEqualTo(new FetchedPosting("744000000001", "Java Developer",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jobs/acme/744000000001",
                "Košice, Slovakia (Slovak Republic)", null));
    }

    /**
     * Фильтр страны — только вакансии с местом в Словакии.
     */
    @Test
    void keepsOnlyPostingsInCountry() {
        pages.put("0", list(group("Košice, Slovakia (Slovak Republic)", job(744000000001L, "Java Developer")),
                group("Prague, Czechia", job(744000000003L, "DevOps Engineer"))));

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(BOARD, "SK");

        assertThat(read.postings()).extracting(FetchedPosting::externalId).containsExactly("744000000001");
    }

    /**
     * Текст и место — со страницы вакансии.
     */
    @Test
    void readsDetailFromJobPostingMarkup() {
        FetchedPosting detail = adapter.detail(BOARD, "744000000001");

        assertThat(detail.content()).contains("Java, Spring Boot");
        assertThat(detail.location()).isEqualTo("Košice, Slovakia (Slovak Republic)");
    }

    /**
     * Отказ на первой странице — источник недоступен; на следующей — неполное чтение.
     */
    @Test
    void failureOnFirstPageIsUnavailableAndOnLaterPagePartial() {
        statuses.put("0", 503);
        assertThat(adapter.read(BOARD)).isInstanceOf(SourceReadResult.Unavailable.class);

        statuses.remove("0");
        pages.put("0", list(group("Košice, Slovakia (Slovak Republic)", job(744000000001L, "Java Developer"))));
        statuses.put("1", 503);
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(BOARD);
        assertThat(read.complete()).isFalse();
        assertThat(read.postings()).hasSize(1);
    }

    /**
     * Идентификатор компании не по формату — запрос не выполняется.
     */
    @Test
    void rejectsMalformedBoard() {
        assertThat(adapter.read("../admin")).isEqualTo(new SourceReadResult.Unavailable(
                new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED, "Not a SmartRecruiters company: ../admin")));
    }

    private static String list(String... groups) {
        return "<html><body><div class=\"js-openings-load\">" + String.join("", groups) + "</div></body></html>";
    }

    private static String group(String location, String... jobs) {
        return "<section class=\"openings-section opening opening--grouped js-group\"><header class=\"opening-header\">"
                + "<h3 class=\"opening-title title\">" + location + "</h3></header><ul class=\"opening-jobs\">"
                + String.join("", jobs) + "</ul></section>";
    }

    private static String job(long id, String title) {
        return "<li class=\"opening-job job\"><a href=\"https://jobs.smartrecruiters.com/acme/" + id + "-"
                + title.toLowerCase().replace(' ', '-') + "\" class=\"link--block details js-job-ad-link\">"
                + "<h4 class=\"details-title job-title\">" + title + "</h4></a></li>";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
