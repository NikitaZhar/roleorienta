package com.roleorienta.worker.adapter.jobposting;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер {@code JobPosting} на заглушке сайта: ссылки кадровой страницы, разбор JSON-LD,
 * страницы без разметки, потолок страниц, отказы страниц и кадровой страницы, не-адрес доски;
 * тела прочитанных страниц отдаются для снимков.
 */
class JobPostingAdapterTests {

    private static final int MAX_PAGES = 10;

    private static final String CAREERS = """
            <html><body>
              <a href="/careers">Careers</a>
              <a href="/jobs/1">Java Developer</a>
              <a href="/jobs/1#apply">Apply</a>
              <a href="jobs/2">QA Engineer</a>
              <a href="/about">About</a>
              <a href="http://example.org/jobs/3">Partner</a>
            </body></html>
            """;

    private static final String JOB_1 = """
            <html><head><script type="application/ld+json">
            {"@context": "https://schema.org", "@type": "JobPosting", "title": " Java Developer ",
             "description": "<p>Java</p>",
             "jobLocation": {"@type": "Place", "address": {"@type": "PostalAddress",
               "addressLocality": "Bratislava", "addressCountry": "SK"}}}
            </script></head><body>Job</body></html>
            """;

    private static final String JOB_2 = """
            <html><head>
            <script type="application/ld+json">{ broken </script>
            <script type="application/ld+json">
            {"@context": "https://schema.org", "@graph": [
              {"@type": "Organization", "name": "Acme"},
              {"@type": ["JobPosting"], "title": "QA Engineer", "jobLocation": [
                {"address": {"addressLocality": "Vienna", "addressCountry": {"@type": "Country", "name": "AT"}}},
                {"address": "Remote, EU"}]}]}
            </script></head><body>Job</body></html>
            """;

    private static final String ABOUT = "<html><body>About us</body></html>";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();

    /** Ответ по пути: код и тело; неизвестный путь (и robots.txt) — 404. */
    private final Map<String, Integer> statuses = new HashMap<>();
    private final Map<String, String> pages = new HashMap<>();

    private HttpServer server;

    /**
     * Заглушка сайта на свободном порту.
     *
     * @throws IOException порт не открылся
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] bytes = pages.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
            int status = statuses.getOrDefault(path, pages.containsKey(path) ? 200 : 404);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        pages.put("/careers", CAREERS);
        pages.put("/jobs/1", JOB_1);
        pages.put("/jobs/2", JOB_2);
        pages.put("/about", ABOUT);
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
     * Читаются только ссылки на тот же хост, без повторов и фрагментов; страница без разметки и
     * испорченный блок JSON-LD пропускаются; {@code @graph}, массив типов и несколько мест.
     */
    @Test
    void readsPostingsFromLinkedPages() {
        assertThat(adapter(MAX_PAGES).read(url("/careers"))).isEqualTo(SourceReadResult.Read.full(List.of(
                new FetchedPosting(url("/jobs/1"), "Java Developer", url("/jobs/1"), "Bratislava, SK", "<p>Java</p>"),
                new FetchedPosting(url("/jobs/2"), "QA Engineer", url("/jobs/2"), "Vienna, AT; Remote, EU", null)),
                List.of(CAREERS, JOB_1, JOB_2, ABOUT)));
    }

    /**
     * Ссылок больше потолка — читаются первые, чтение неполное.
     */
    @Test
    void readsPartiallyOverCeiling() {
        assertThat(adapter(1).read(url("/careers"))).isEqualTo(SourceReadResult.Read.partial(List.of(
                new FetchedPosting(url("/jobs/1"), "Java Developer", url("/jobs/1"), "Bratislava, SK", "<p>Java</p>")),
                PartialReason.PAGE_LIMIT, List.of(CAREERS, JOB_1)));
    }

    /**
     * Временный отказ страницы вакансии — чтение неполное.
     */
    @Test
    void readsPartiallyOnTemporaryPageFailure() {
        statuses.put("/jobs/2", 503);

        SourceReadResult result = adapter(MAX_PAGES).read(url("/careers"));

        assertThat(result).isInstanceOf(SourceReadResult.Read.class);
        assertThat(((SourceReadResult.Read) result).partialReason()).isEqualTo(PartialReason.PAGE_FAILED);
        assertThat(((SourceReadResult.Read) result).postings()).hasSize(1);
    }

    /**
     * Удалённая страница (404) пропускается — чтение остаётся полным.
     */
    @Test
    void skipsMissingPage() {
        pages.remove("/jobs/2");

        SourceReadResult result = adapter(MAX_PAGES).read(url("/careers"));

        assertThat(result).isInstanceOf(SourceReadResult.Read.class);
        assertThat(((SourceReadResult.Read) result).complete()).isTrue();
        assertThat(((SourceReadResult.Read) result).postings()).hasSize(1);
    }

    /**
     * 503 кадровой страницы — источник временно недоступен.
     */
    @Test
    void reportsUnavailableCareersPage() {
        statuses.put("/careers", 503);

        assertThat(adapter(MAX_PAGES).read(url("/careers"))).isEqualTo(new SourceReadResult.Unavailable(
                new HttpResult.TemporaryFailure("HTTP 503", Duration.ZERO)));
    }

    /**
     * Доска не абсолютный http(s)-адрес — запрос не выполняется.
     */
    @Test
    void rejectsNonUrlBoard() {
        assertThat(adapter(MAX_PAGES).read("acme.com")).isEqualTo(new SourceReadResult.Unavailable(
                new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED, "Not a careers page URL: acme.com")));
    }

    private JobPostingAdapter adapter(int maxPages) {
        return new JobPostingAdapter(httpClient, new JobPostingProperties(maxPages));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
