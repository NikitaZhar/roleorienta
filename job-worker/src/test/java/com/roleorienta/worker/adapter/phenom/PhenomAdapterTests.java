package com.roleorienta.worker.adapter.phenom;

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
 * Адаптер Phenom на настоящих образцах ({@code docs/samples/phenom-dhl-*.html}, сняты 2026-10-06): две страницы поиска
 * DHL с фильтром Словакии (объявлено 25, по 10) и страница вакансии; третья страница — синтетическая в тех же данных.
 */
class PhenomAdapterTests {

    private static final String DHL = "careers.dhl.com/eu/sk";
    private static final Path SAMPLES = Path.of("../docs/samples");
    private static final Pattern FROM = Pattern.compile("from=(\\d+)");
    private static final String SEARCH = "/" + DHL + "/search-results";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /** Ответ по пути и {@code from}: «путь|сдвиг» → тело; нет — 404; {@code "503"} — 503. */
    private final Map<String, String> pages = new HashMap<>();
    /** Запросы страницы поиска (без robots.txt и страниц вакансий). */
    private final List<String> queries = new ArrayList<>();

    private HttpServer server;
    private PhenomAdapter adapter;

    /**
     * @throws IOException порт не открылся или образец не прочитан
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if (exchange.getRequestURI().getPath().endsWith("/search-results")) {
                queries.add(query == null ? "" : query);
            }
            Matcher from = FROM.matcher(query == null ? "" : query);
            String body = pages.get(exchange.getRequestURI().getPath() + "|" + (from.find() ? from.group(1) : ""));
            int status = body == null ? 404 : "503".equals(body) ? 503 : 200;
            byte[] bytes = status == 200 ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        adapter = new PhenomAdapter(httpClient, new PhenomProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{board}", 40));
        pages.put(SEARCH + "|0", sample("phenom-dhl-search.html"));
        pages.put(SEARCH + "|10", sample("phenom-dhl-search-2.html"));
        pages.put("/" + DHL + "/job/AV-366620|", sample("phenom-dhl-job.html"));
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
     * Три страницы (объявлено 25): 10 + 10 образцов и 5 синтетических, одна из них — Австрия: в списке Словакии 24
     * вакансии, чтение полное, фильтр страны — в каждом запросе.
     */
    @Test
    void readsAllPagesAndKeepsOnlyCountry() {
        pages.put(SEARCH + "|20", third());

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(DHL, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).hasSize(24);
        assertThat(read.postings().get(0)).isEqualTo(new FetchedPosting("AV-366620",
                "Debrief manipulant (part-time) - (Male/Female)", base() + "/" + DHL + "/job/AV-366620",
                "Bratislava, Bratislavský kraj, Slovakia", null));
        assertThat(read.postings()).extracting(FetchedPosting::externalId).doesNotContain("X-4");
        assertThat(queries).containsExactly("qcountry=Slovakia&from=0", "qcountry=Slovakia&from=10",
                "qcountry=Slovakia&from=20");
    }

    /**
     * Отказ на третьей странице — неполное чтение с прочитанным; на первой, страница без данных или не доска —
     * источник недоступен.
     */
    @Test
    void failuresGivePartialOrUnavailable() {
        pages.put(SEARCH + "|20", "503");
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(DHL, "SK");
        assertThat(read.partialReason()).isEqualTo(PartialReason.PAGE_FAILED);
        assertThat(read.postings()).hasSize(20);

        pages.put(SEARCH + "|0", "<html><body>Maintenance</body></html>");
        assertThat(adapter.read(DHL, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
        pages.put(SEARCH + "|0", "503");
        assertThat(adapter.read(DHL, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
        assertThat(adapter.read("not a board", "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
    }

    /**
     * Текст и место — из данных страницы вакансии.
     */
    @Test
    void readsJobDescription() {
        FetchedPosting detail = adapter.detail(DHL, "AV-366620");

        assertThat(detail.content()).contains("Náplň práce");
        assertThat(detail.location()).isEqualTo("Bratislava, Bratislavský kraj, Slovakia");
        assertThat(adapter.detail(DHL, "AV-1")).isNull();
        assertThat(adapter.detail(DHL, "../x")).isNull();
    }

    private static String third() {
        StringBuilder jobs = new StringBuilder();
        for (int index = 0; index < 5; index++) {
            String country = index == 4 ? "Austria" : "Slovakia";
            jobs.append(index == 0 ? "" : ",").append("{\"jobId\":\"X-").append(index).append("\",\"title\":\"Job ")
                    .append(index).append("\",\"location\":\"Nitra, ").append(country).append("\",\"country\":\"")
                    .append(country).append("\"}");
        }
        return "<script>phApp.ddo = {\"eagerLoadRefineSearch\":{\"totalHits\":25,\"data\":{\"jobs\":[" + jobs
                + "]}}}; phApp.pageId = \"page17\";</script>";
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String sample(String name) throws IOException {
        return Files.readString(SAMPLES.resolve(name));
    }
}
