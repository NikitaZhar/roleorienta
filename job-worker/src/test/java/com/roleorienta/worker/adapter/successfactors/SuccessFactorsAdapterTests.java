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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер SuccessFactors на настоящих образцах ответов ({@code docs/samples/sf-*.html}, сняты 2026-10-06): список
 * ZF в Словакии (25 строк из 36), список Kaufland по запросу «Slovensko» (20 вакансий Германии), страница вакансии
 * ZF; вторая страница ZF — синтетическая в той же разметке. Сняты 2026-10-15 (§115): плитки BIC (Словакия и Греция,
 * поля города и страны) и Schaeffler (15 плиток, только город), новый поиск SAP Danfoss — страница поиска и две
 * страницы ответа JSON (17 вакансий: 10 в Словакии, 7 в Польше и Румынии).
 */
class SuccessFactorsAdapterTests {

    private static final String ZF = "jobs.zf.com";
    private static final String KAUFLAND = "jobs.kaufland.com";
    private static final Path SAMPLES = Path.of("../docs/samples");
    private static final Pattern START_ROW = Pattern.compile("startrow=(\\d+)");
    private static final String FIRST_ZF_JOB = "/job/Levice-ZF-PS-Coordinator-NI-934-01/1439374533/";
    private static final String BIC = "careers.bic.com";
    private static final String SCHAEFFLER = "jobs.schaeffler.com";
    private static final String DANFOSS = "jobs.danfoss.com";
    private static final String JOBS_SEARCH = "/" + DANFOSS + "/services/recruiting/v1/jobs";
    /** Токен страницы поиска Danfoss ({@code var CSRFToken} образца). */
    private static final String DANFOSS_TOKEN = "2ff0db7c-0a2a-4909-8a92-808f55f43cde";
    private static final Pattern PAGE_NUMBER = Pattern.compile("\"pageNumber\":(\\d+)");

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /**
     * Ответ по пути и {@code startrow}: «путь|сдвиг» → тело; POST нового поиска — «путь|p<номер страницы>»; нет — 404;
     * {@code "503"} — 503.
     */
    private final Map<String, String> pages = new HashMap<>();
    /** Запросы POST нового поиска: заголовок {@code X-CSRF-Token} и тело. */
    private final List<String> searches = new ArrayList<>();
    /** Заголовок {@code Cookie} запросов POST нового поиска; страницы GET выдают cookie сессии (аудит §121). */
    private final List<String> searchCookies = new ArrayList<>();

    private HttpServer server;
    private SuccessFactorsAdapter adapter;

    /**
     * @throws IOException порт не открылся или образец не прочитан
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String key;
            if ("POST".equals(exchange.getRequestMethod())) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Matcher number = PAGE_NUMBER.matcher(request);
                key = exchange.getRequestURI().getPath() + "|p" + (number.find() ? number.group(1) : "");
                searches.add(exchange.getRequestHeaders().getFirst("X-CSRF-Token") + " " + request);
                searchCookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            } else {
                exchange.getResponseHeaders().set("Set-Cookie", "session=sf; Path=/");
                String query = exchange.getRequestURI().getRawQuery();
                Matcher row = START_ROW.matcher(query == null ? "" : query);
                key = exchange.getRequestURI().getPath() + "|" + (row.find() ? row.group(1) : "");
            }
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
        pages.put("/" + BIC + "/search/|0", sample("sf-bic.html"));
        pages.put("/" + SCHAEFFLER + "/search/|0", sample("sf-schaeffler.html"));
        pages.put("/" + DANFOSS + "/search/|0", sample("sf-danfoss.html"));
        pages.put(JOBS_SEARCH + "|p0", sample("sf-danfoss-0.json"));
        pages.put(JOBS_SEARCH + "|p1", sample("sf-danfoss-1.json"));
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
     * Kaufland на запрос по месту ответил «ничего не найдено» ({@code #noresults}) и показал последние вакансии
     * Германии: вакансий страны нет, чтение полное. Ответ «ничего не найдено» без строк и без числа вакансий — тоже
     * полное пустое чтение, а не заглушка (аудит §66).
     */
    @Test
    void noResultsAnswerIsEmptyFullRead() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(KAUFLAND, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).isEmpty();

        pages.put("/" + ZF + "/search/|0", "<html><body><div id=\"noresults\">No jobs</div></body></html>");
        SourceReadResult.Read empty = (SourceReadResult.Read) adapter.read(ZF, "SK");
        assertThat(empty.complete()).isTrue();
        assertThat(empty.postings()).isEmpty();
    }

    /**
     * Отказ на второй странице — неполное чтение с прочитанным; на первой или первая страница — не список (заглушка;
     * аудит §65) — источник недоступен, вакансии не закрываются.
     */
    @Test
    void failuresGivePartialOrUnavailable() {
        pages.put("/" + ZF + "/search/|25", "503");
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(ZF, "SK");
        assertThat(read.partialReason()).isEqualTo(PartialReason.PAGE_FAILED);
        assertThat(read.postings()).hasSize(25);

        pages.put("/" + ZF + "/search/|0", "503");
        assertThat(adapter.read(ZF, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
        pages.put("/" + ZF + "/search/|0", "<html><body>Maintenance</body></html>");
        assertThat(adapter.read(ZF, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
        assertThat(adapter.read("not a host", "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
    }

    /**
     * Плитки BIC: место — поля города и страны; вакансия Греции (страна GR) не входит; путь с разделом сайта
     * ({@code /BIC/job/…}) — внешний id; число вакансий — «Showing 1 to 2 of 2 Jobs», чтение полное (§115).
     */
    @Test
    void tilesKeepOnlyCountry() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(BIC, "SK");

        assertThat(read.complete()).isTrue();
        String path = "/BIC/job/Sered-Logistic-Operations-Coordinator-926-01/1371080355/";
        assertThat(read.postings()).containsExactly(new FetchedPosting(path, "Logistic Operations Coordinator",
                base() + "/" + BIC + path, "Sered, SK", null));
    }

    /**
     * Плитки Schaeffler — только город, полей места и страны нет: страну подтверждает поиск сайта, все 15 вакансий
     * входят (решение владельца, §115).
     */
    @Test
    void tilesWithoutCountryTrustSiteSearch() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(SCHAEFFLER, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).hasSize(15).extracting(FetchedPosting::location).containsOnly("Kysucké Nové Mesto");
    }

    /**
     * Новый поиск SAP Danfoss: запрос JSON с токеном и языком страницы и названием страны, две страницы по 10 до
     * {@code totalJobs} 17; в списке Словакии — 10 вакансий с местом «…, SVK», вакансии Польши и Румынии — нет; путь
     * вакансии — с языком (§115); запросы списка несут cookie, выданную страницей поиска в том же чтении (аудит §121).
     */
    @Test
    void jobSearchReadsJsonPages() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(DANFOSS, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).hasSize(10).extracting(FetchedPosting::location)
                .allMatch(place -> place.endsWith(", SVK"));
        String path = "/job/Logistics-Specialist/52241-en_GB/";
        assertThat(read.postings().get(1)).isEqualTo(new FetchedPosting(path, "Logistics Specialist",
                base() + "/" + DANFOSS + path, "Vrbove, SVK", null));
        assertThat(searches).hasSize(2).allMatch(search -> search.startsWith(DANFOSS_TOKEN + " ")
                && search.contains("\"locale\":\"en_GB\"") && search.contains("\"location\":\"Slovakia\""));
        assertThat(searchCookies).containsExactly("session=sf", "session=sf");
    }

    /**
     * Новый поиск SAP: ответ с ошибкой сайта на первой странице — источник недоступен; отказ на второй — неполное
     * чтение с прочитанным (§115).
     */
    @Test
    void jobSearchFailuresGivePartialOrUnavailable() {
        pages.put(JOBS_SEARCH + "|p1", "503");
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(DANFOSS, "SK");
        assertThat(read.partialReason()).isEqualTo(PartialReason.PAGE_FAILED);
        assertThat(read.postings()).hasSize(10);

        pages.put(JOBS_SEARCH + "|p0", "{\"error\":{\"code\":\"Error\",\"message\":\"Error retrieving jobs\"},"
                + "\"totalJobs\":0}");
        assertThat(adapter.read(DANFOSS, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
    }

    /**
     * Текст — {@code span.jobdescription} страницы вакансии; путь нового поиска — с языком.
     */
    @Test
    void readsJobDescription() {
        FetchedPosting detail = adapter.detail(ZF, FIRST_ZF_JOB);

        assertThat(detail.content()).contains("We are looking for a ZF PS Coordinator");
        assertThat(adapter.detail(ZF, "/not-a-job")).isNull();

        String withLocale = "/job/Logistics-Specialist/52241-en_GB/";
        pages.put("/" + DANFOSS + withLocale + "|", pages.get("/" + ZF + FIRST_ZF_JOB + "|"));
        assertThat(adapter.detail(DANFOSS, withLocale)).isNotNull();
    }

    /**
     * Страна в месте — последнее поле из двух заглавных букв или трёхбуквенный код страны, иначе — название страны.
     */
    @Test
    void recognisesCountryOfLocation() {
        assertThat(SuccessFactorsAdapter.inCountry("Levice, NI, SK, 934 01", "SK")).isTrue();
        assertThat(SuccessFactorsAdapter.inCountry("Regina, SK, CA, S4P 3Y2", "SK")).isFalse();
        assertThat(SuccessFactorsAdapter.inCountry("Bratislava, Slovakia", "SK")).isTrue();
        assertThat(SuccessFactorsAdapter.inCountry("Vrbove, SVK", "SK")).isTrue();
        assertThat(SuccessFactorsAdapter.inCountry("Bucharest, ROU", "SK")).isFalse();
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
