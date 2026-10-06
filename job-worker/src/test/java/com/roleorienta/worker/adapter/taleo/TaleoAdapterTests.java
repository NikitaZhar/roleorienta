package com.roleorienta.worker.adapter.taleo;

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
 * Адаптер Taleo на настоящих образцах MOL Group ({@code docs/samples/taleo-*}, сняты 2026-10-06): страница поиска
 * (номер портала), поиск без фильтра (фасет стран), первая страница Словакии (25 из 52), страница вакансии; вторая и
 * третья страницы Словакии — синтетические в том же формате. Без заголовка {@code tz} заглушка отвечает 500, как Taleo.
 */
class TaleoAdapterTests {

    private static final String BOARD = "molgroup.taleo.net/external";
    private static final String SECTION = "/molgroup.taleo.net/careersection/external";
    private static final String SEARCH = "/molgroup.taleo.net/careersection/rest/jobboard/searchjobs";
    private static final Path SAMPLES = Path.of("../docs/samples");
    private static final Pattern PAGE = Pattern.compile("\"pageNo\":(\\d+)");
    private static final String SLOVAKIA = "4305100397";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /** Ответы: путь (GET) или «search|фильтр|страница» (POST) → тело; нет — 404; {@code "503"} — 503. */
    private final Map<String, String> answers = new HashMap<>();

    private HttpServer server;
    private TaleoAdapter adapter;

    /**
     * @throws IOException порт не открылся или образец не прочитан
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Matcher page = PAGE.matcher(body);
                key = "search|" + (body.contains(SLOVAKIA) ? SLOVAKIA : "") + "|" + (page.find() ? page.group(1) : "");
                if (exchange.getRequestHeaders().getFirst("tz") == null) {
                    key = "no tz";
                }
            } else if (exchange.getRequestURI().getQuery() != null) {
                key += "?" + exchange.getRequestURI().getQuery();
            }
            String answer = "no tz".equals(key) ? "500" : answers.get(key);
            int status = answer == null ? 404 : answer.matches("\\d{3}") ? Integer.parseInt(answer) : 200;
            byte[] bytes = status == 200 ? answer.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        adapter = new TaleoAdapter(httpClient, new TaleoProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{host}", 40));
        answers.put(SECTION + "/jobsearch.ftl", sample("taleo-mol.html"));
        answers.put("search||1", sample("taleo-mol-all.json"));
        answers.put("search|" + SLOVAKIA + "|1", sample("taleo-mol-search.json"));
        answers.put("search|" + SLOVAKIA + "|2", page(2, 25));
        answers.put("search|" + SLOVAKIA + "|3", page(3, 2));
        answers.put(SECTION + "/jobdetail.ftl?job=26001720&lang=en", sample("taleo-mol-job.html"));
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
     * Id Словакии — из фасета поиска без фильтра; три страницы с фильтром — 52 вакансии, чтение полное.
     */
    @Test
    void readsCountryThroughLocationFacet() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(BOARD, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).hasSize(52);
        assertThat(read.postings().get(0)).isEqualTo(new FetchedPosting("26001720",
                "Senior PLC programátor/programátorka",
                base() + SECTION + "/jobdetail.ftl?job=26001720&lang=en", "Slovakia-Bratislava", null));
    }

    /**
     * Страны нет в фасете — вакансий в ней нет: чтение полное и пустое.
     */
    @Test
    void countryWithoutPostingsGivesEmptyList() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(BOARD, "DE");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).isEmpty();
    }

    /**
     * Аудит §65: фасета стран нет (а вакансии есть) — источник временно недоступен, а не пустое полное чтение; поиск
     * отвечает 404 — отказ передаётся как есть (постоянный), а не временная ошибка разбора.
     */
    @Test
    void missingCountryFacetOrSearchFailureIsUnavailable() {
        answers.put("search||1",
                "{\"requisitionList\":[],\"pagingData\":{\"totalCount\":52},\"facetResults\":[]}");
        SourceReadResult noFacet = adapter.read(BOARD, "SK");
        assertThat(noFacet).isInstanceOf(SourceReadResult.Unavailable.class);
        assertThat(((SourceReadResult.Unavailable) noFacet).failure()).isInstanceOf(HttpResult.TemporaryFailure.class);

        answers.remove("search||1");
        SourceReadResult gone = adapter.read(BOARD, "SK");
        assertThat(((SourceReadResult.Unavailable) gone).failure())
                .isEqualTo(new HttpResult.PermanentFailure(HttpResult.Kind.NOT_FOUND, "HTTP 404"));
    }

    /**
     * Отказ на второй странице — неполное чтение; отказ страницы поиска или не доска — источник недоступен.
     */
    @Test
    void failuresGivePartialOrUnavailable() {
        answers.put("search|" + SLOVAKIA + "|2", "503");
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(BOARD, "SK");
        assertThat(read.partialReason()).isEqualTo(PartialReason.PAGE_FAILED);
        assertThat(read.postings()).hasSize(25);

        answers.put(SECTION + "/jobsearch.ftl", "503");
        assertThat(adapter.read(BOARD, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
        assertThat(adapter.read("molgroup.example.com/external", "SK"))
                .isInstanceOf(SourceReadResult.Unavailable.class);
    }

    /**
     * Текст — блоки {@code !*!} поля {@code initialHistory}, повтор блока — один раз; «+» и «%» без кода (в образце —
     * «107%;m») в тексте не теряются.
     */
    @Test
    void readsJobDescription() {
        FetchedPosting detail = adapter.detail(BOARD, "26001720");

        assertThat(detail.content()).contains("PLC, SCADA");
        assertThat(adapter.detail(BOARD, "1")).isNull();
        assertThat(TaleoAdapter.description("x!|!!*!%3Cp%3EC%2B%2B a+b%3C/p%3E!|!!*!%3Cp%3EC%2B%2B a+b%3C/p%3E!|!y"))
                .isEqualTo("<p>C++ a+b</p>");
        assertThat(TaleoAdapter.percentDecoded("107%;m %C5%A1 50%")).isEqualTo("107%;m š 50%");
    }

    private static String page(int number, int size) {
        StringBuilder jobs = new StringBuilder();
        for (int index = 0; index < size; index++) {
            jobs.append(index == 0 ? "" : ",").append("{\"contestNo\":\"S").append(number).append('-').append(index)
                    .append("\",\"column\":[\"Job ").append(index)
                    .append("\",\"[\\\"Slovakia-Nitra\\\"]\",\"Sep 1, 2026\"],\"locationsColumns\":[1]}");
        }
        return "{\"requisitionList\":[" + jobs + "],\"pagingData\":{\"currentPageNo\":" + number
                + ",\"pageSize\":25,\"totalCount\":52}}";
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String sample(String name) throws IOException {
        return Files.readString(SAMPLES.resolve(name));
    }
}
