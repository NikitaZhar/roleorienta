package com.roleorienta.worker.adapter.successfactors;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.TestHttpClients;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер SuccessFactors на настоящих образцах ответов ({@code docs/samples/sf-*.html}, сняты 2026-10-06): список
 * ZF в Словакии (25 строк из 36), список Kaufland по запросу «Slovensko» (20 вакансий Германии), страница вакансии
 * ZF; вторая страница ZF — синтетическая в той же разметке.
 */
class SuccessFactorsAdapterTests {

    private static final String ZF = "jobs.zf.com";
    private static final String KAUFLAND = "jobs.kaufland.com";
    private static final Path SAMPLES = Path.of("../docs/samples");
    private static final Pattern START_ROW = Pattern.compile("startrow=(\\d+)");
    private static final String FIRST_ZF_JOB = "/job/Levice-ZF-PS-Coordinator-NI-934-01/1439374533/";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /** Ответ по пути и {@code startrow}: «путь|сдвиг» → тело; нет — 404; {@code "503"} — 503. */
    private final Map<String, String> pages = new HashMap<>();

    private HttpServer server;
    private SuccessFactorsAdapter adapter;

    /**
     * @throws IOException порт не открылся или образец не прочитан
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            Matcher row = START_ROW.matcher(query == null ? "" : query);
            String key = exchange.getRequestURI().getPath() + "|" + (row.find() ? row.group(1) : "");
            String body = pages.get(key);
            int status = body == null ? 404 : "503".equals(body) ? 503 : 200;
            byte[] bytes = status == 200 ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        adapter = new SuccessFactorsAdapter(httpClient, new SuccessFactorsProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{host}", 40));
        pages.put("/" + ZF + "/search/|0", sample("sf-zf.html"));
        pages.put("/" + KAUFLAND + "/search/|0", sample("sf-kaufland.html"));
        pages.put("/" + ZF + FIRST_ZF_JOB + "|", sample("sf-zf-job.html"));
    }

    /**
     * @throws IOException ошибка закрытия клиента
     */
    @AfterEach
    void stop() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    /**
     * Две страницы ZF (объявлено 36): 25 строк образца и 11 синтетических, одна из них — Канада («Regina, SK, CA»,
     * SK — провинция): в списке Словакии 35 вакансий, чтение полное.
     */
    @Test
    void readsAllPagesAndKeepsOnlyCountry() {
        StringBuilder second = new StringBuilder("<table>");
        for (int index = 0; index < 10; index++) {
            second.append(row("/job/Levice-Operator-" + index + "/" + (900 + index) + "/", "Operator " + index,
                    "Levice, NI, SK, 934 01"));
        }
        second.append(row("/job/Regina-Driver/999/", "Driver", "Regina, SK, CA, S4P 3Y2")).append("</table>");
        pages.put("/" + ZF + "/search/|25", second.toString());

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(ZF, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).hasSize(35);
        assertThat(read.postings().get(0)).isEqualTo(new FetchedPosting(FIRST_ZF_JOB, "ZF PS Coordinator",
                base() + "/" + ZF + FIRST_ZF_JOB, "Levice, NI, SK, 934 01", null));
        assertThat(read.postings()).extracting(FetchedPosting::title).doesNotContain("Driver");
    }

    /**
     * Kaufland на запрос по месту ответил вакансиями Германии: в список Словакии не входит ни одна, чтение полное.
     */
    @Test
    void dropsRowsOfOtherCountries() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(KAUFLAND, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).isEmpty();
    }

    /**
     * Отказ на второй странице — неполное чтение с прочитанным; на первой — источник недоступен.
     */
    @Test
    void failuresGivePartialOrUnavailable() {
        pages.put("/" + ZF + "/search/|25", "503");
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(ZF, "SK");
        assertThat(read.partialReason()).isEqualTo(PartialReason.PAGE_FAILED);
        assertThat(read.postings()).hasSize(25);

        pages.put("/" + ZF + "/search/|0", "503");
        assertThat(adapter.read(ZF, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
        assertThat(adapter.read("not a host", "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
    }

    /**
     * Текст — {@code span.jobdescription} страницы вакансии.
     */
    @Test
    void readsJobDescription() {
        FetchedPosting detail = adapter.detail(ZF, FIRST_ZF_JOB);

        assertThat(detail.content()).contains("We are looking for a ZF PS Coordinator");
        assertThat(adapter.detail(ZF, "/not-a-job")).isNull();
    }

    /**
     * Страна в месте — последнее поле из двух заглавных букв, иначе — название страны.
     */
    @Test
    void recognisesCountryOfLocation() {
        assertThat(SuccessFactorsAdapter.inCountry("Levice, NI, SK, 934 01", "SK")).isTrue();
        assertThat(SuccessFactorsAdapter.inCountry("Regina, SK, CA, S4P 3Y2", "SK")).isFalse();
        assertThat(SuccessFactorsAdapter.inCountry("Bratislava, Slovakia", "SK")).isTrue();
        assertThat(SuccessFactorsAdapter.inCountry(null, "SK")).isFalse();
    }

    private static String row(String path, String title, String location) {
        return "<tr class=\"data-row\"><td><a class=\"jobTitle-link\" href=\"" + path + "\">" + title
                + "</a><span class=\"jobLocation\">" + location + "</span></td></tr>";
    }

    private static String sample(String name) throws IOException {
        return Files.readString(SAMPLES.resolve(name), StandardCharsets.UTF_8);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
