package com.roleorienta.worker.adapter.workday;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.PostingCheck;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер Workday на заглушке: страницы списка, неполное чтение, отказ, чужой хост, текст
 * публикации, фильтр по стране через фасеты, проверка публикации деталью.
 */
class WorkdayAdapterTests {

    private static final String HOST = "acme.wd3.myworkdayjobs.com";
    private static final String BOARD = HOST + "/External";
    private static final String JOBS_PATH = "/" + HOST + "/wday/cxs/acme/External/jobs";
    private static final int PAGE_SIZE = 2;
    private static final Pattern OFFSET = Pattern.compile("\"offset\":(\\d+)");
    private static final String SK_FACET = "{\"locationCountry\":[\"sk-id\"]}";
    private static final String FACETS = ",\"facets\":[{\"facetParameter\":\"locationMainGroup\",\"values\":["
            + "{\"facetParameter\":\"locationCountry\",\"values\":["
            + "{\"descriptor\":\"Austria\",\"id\":\"at-id\",\"count\":5},"
            + "{\"descriptor\":\"Slovakia\",\"id\":\"sk-id\",\"count\":1}]}]}]";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();

    /** Ответ по смещению: код и тело; {@code filteredPages} — на запросы с фильтром Словакии. */
    private final Map<Integer, String> pages = new HashMap<>();
    private final Map<Integer, String> filteredPages = new HashMap<>();
    private final Map<Integer, Integer> statuses = new HashMap<>();

    private HttpServer server;
    private WorkdayAdapter adapter;

    /**
     * Заглушка списка: ответ выбирается по {@code offset} из тела запроса.
     *
     * @throws IOException порт не открылся
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(JOBS_PATH, exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher offset = OFFSET.matcher(request);
            int key = offset.find() ? Integer.parseInt(offset.group(1)) : -1;
            Map<Integer, String> responses = request.contains(SK_FACET) ? filteredPages : pages;
            byte[] bytes = responses.getOrDefault(key, "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.createContext("/" + HOST + "/wday/cxs/acme/External/job/", exchange -> {
            if (exchange.getRequestURI().getPath().contains("/Gone")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String additional = exchange.getRequestURI().getPath().contains("/Multi")
                    ? "\"additionalLocations\":[\"IND.Pune\",\"USA.VA.Reston\"]," : "";
            byte[] bytes = ("{\"jobPostingInfo\":{\"title\":\"Java Developer\",\"location\":\"Vienna\","
                    + additional + "\"jobDescription\":\"<p>Java</p>\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        adapter = new WorkdayAdapter(httpClient, new WorkdayProperties(stubUrl() + "/{host}", PAGE_SIZE, 10));
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
     * Две страницы; {@code total} только на первой (как у Workday) — чтение полное.
     */
    @Test
    void readsAllPages() {
        pages.put(0, page(3, "Java Developer", "QA Engineer"));
        pages.put(2, page(0, "DevOps Engineer"));

        SourceReadResult result = adapter.read(BOARD);

        assertThat(result).isInstanceOf(SourceReadResult.Read.class);
        SourceReadResult.Read read = (SourceReadResult.Read) result;
        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).extracting(FetchedPosting::title)
                .containsExactly("Java Developer", "QA Engineer", "DevOps Engineer");
        assertThat(read.postings().get(0)).isEqualTo(new FetchedPosting("/job/Bratislava/Java-Developer",
                "Java Developer", stubUrl() + "/" + HOST + "/External/job/Bratislava/Java-Developer",
                "Bratislava", null));
    }

    /**
     * Отказ на второй странице — неполное чтение с тем, что успели прочитать.
     */
    @Test
    void failureOnLaterPageGivesPartialRead() {
        pages.put(0, page(3, "Java Developer", "QA Engineer"));
        statuses.put(2, 503);

        SourceReadResult result = adapter.read(BOARD);

        assertThat(result).isInstanceOf(SourceReadResult.Read.class);
        assertThat(((SourceReadResult.Read) result).partialReason()).isEqualTo(PartialReason.PAGE_FAILED);
        assertThat(((SourceReadResult.Read) result).postings()).hasSize(2);
    }

    /**
     * Отказ на первой странице — источник недоступен.
     */
    @Test
    void failureOnFirstPageIsUnavailable() {
        statuses.put(0, 503);

        assertThat(adapter.read(BOARD)).isEqualTo(new SourceReadResult.Unavailable(
                new HttpResult.TemporaryFailure("HTTP 503", Duration.ZERO)));
    }

    /**
     * Деталь по {@code externalPath}: текст — {@code jobDescription}, места — {@code location} и
     * {@code additionalLocations} через «; ».
     */
    @Test
    void readsDetailWithAdditionalLocations() {
        FetchedPosting detail = adapter.detail(BOARD, "/job/Multi/Java-Developer");

        assertThat(detail.content()).isEqualTo("<p>Java</p>");
        assertThat(detail.location()).isEqualTo("Vienna; IND.Pune; USA.VA.Reston");
    }

    /**
     * Сводка «2 Locations» в списке — место не получено ({@code null}), места даст деталь.
     */
    @Test
    void locationsSummaryInListIsNotALocation() {
        pages.put(0, page(1, "Java Developer").replace("\"locationsText\":\"Bratislava\"",
                "\"locationsText\":\"2 Locations\""));

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(BOARD);

        assertThat(read.postings().get(0).location()).isNull();
    }

    /**
     * Страна: первый ответ без фильтра даёт фасет (вложенный в группу), дальше список читается с
     * {@code appliedFacets} Словакии.
     */
    @Test
    void readsCountryFilteredList() {
        pages.put(0, withFacets(page(6, "Java Developer", "QA Engineer")));
        filteredPages.put(0, page(1, "Java Developer"));

        SourceReadResult result = adapter.read(BOARD, "SK");

        assertThat(((SourceReadResult.Read) result).complete()).isTrue();
        assertThat(((SourceReadResult.Read) result).postings()).extracting(FetchedPosting::title)
                .containsExactly("Java Developer");
    }

    /**
     * Страны нет среди значений фасета — у доски нет её публикаций: пустой полный список.
     */
    @Test
    void readsEmptyListWhenCountryHasNoPostings() {
        pages.put(0, withFacets(page(6, "Java Developer", "QA Engineer")));

        SourceReadResult result = adapter.read(BOARD, "DE");

        assertThat(((SourceReadResult.Read) result).complete()).isTrue();
        assertThat(((SourceReadResult.Read) result).postings()).isEmpty();
    }

    /**
     * Деталь есть — публикация на месте с новыми сведениями; 404 — её нет.
     */
    @Test
    void checksPostingByDetail() {
        assertThat(adapter.check(BOARD, "/job/Vienna/Java-Developer")).isEqualTo(new PostingCheck.Present(
                new FetchedPosting("/job/Vienna/Java-Developer", "Java Developer",
                        stubUrl() + "/" + BOARD + "/job/Vienna/Java-Developer", "Vienna", "<p>Java</p>")));
        assertThat(adapter.check(BOARD, "/job/Gone")).isEqualTo(new PostingCheck.Absent());
    }

    /**
     * Публикации в стране — одним запросом по фасету страны: Словакия есть — да, Германии нет —
     * нет; официальное название («Slovak Republic») — да; фасета страны нет — ответа нет.
     */
    @Test
    void answersWhetherBoardHasPostingsInCountryByFacet() {
        pages.put(0, withFacets(page(6, "Java Developer", "QA Engineer")));
        assertThat(adapter.hasPostingsIn(BOARD, "SK")).contains(true);
        assertThat(adapter.hasPostingsIn(BOARD, "DE")).contains(false);

        pages.put(0, withFacets(page(6, "Java Developer")).replace("\"Slovakia\"", "\"Slovak Republic\""));
        assertThat(adapter.hasPostingsIn(BOARD, "SK")).contains(true);

        pages.put(0, page(6, "Java Developer"));
        assertThat(adapter.hasPostingsIn(BOARD, "SK")).isEmpty();
    }

    /**
     * Хост не Workday — запрос не выполняется.
     */
    @Test
    void rejectsForeignHost() {
        assertThat(adapter.read("example.com/External")).isEqualTo(new SourceReadResult.Unavailable(
                new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED, "Not a Workday board: example.com/External")));
    }

    private static String page(int total, String... titles) {
        StringBuilder json = new StringBuilder("{\"total\":" + total + ",\"jobPostings\":[");
        for (int index = 0; index < titles.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"title\":\"").append(titles[index])
                    .append("\",\"externalPath\":\"/job/Bratislava/").append(titles[index].replace(' ', '-'))
                    .append("\",\"locationsText\":\"Bratislava\"}");
        }
        return json.append("]}").toString();
    }

    private static String withFacets(String page) {
        return page.substring(0, page.length() - 1) + FACETS + "}";
    }

    private String stubUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
