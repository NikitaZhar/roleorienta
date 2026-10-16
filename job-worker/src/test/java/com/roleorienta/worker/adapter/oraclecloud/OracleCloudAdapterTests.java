package com.roleorienta.worker.adapter.oraclecloud;

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
import java.time.Instant;
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
 * Адаптер Oracle Recruiting Cloud на настоящих образцах ответов ({@code docs/samples/oracle-*.json}, сняты 2026-10-15,
 * §117): вакансии Словакии Garrett Motion (7, все в Словакии) и Emerson (4; одна в Германии с дополнительным местом в
 * Словакии), текст вакансии Vertiv. Список с вакансией другой страны и вторая страница — синтетические в том же формате.
 */
class OracleCloudAdapterTests {

    private static final String GARRETT = "ehth.fa.em2.oraclecloud.com/CX_2001";
    private static final String EMERSON = "hdjq.fa.us2.oraclecloud.com/CX_1";
    private static final String VERTIV = "egup.fa.us2.oraclecloud.com/CX";
    private static final String SYNTHETIC = "test.fa.em2.oraclecloud.com/CX";
    private static final String LIST = "/hcmRestApi/resources/latest/recruitingCEJobRequisitions";
    private static final String DETAIL = "/hcmRestApi/resources/latest/recruitingCEJobRequisitionDetails";
    private static final Path SAMPLES = Path.of("../docs/samples");
    private static final Pattern OFFSET = Pattern.compile("offset=(\\d+)");

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /** Ответ по хосту, пути и сдвигу: «/хост/путь|сдвиг» → тело (у текста вакансии сдвига нет); нет — 404; «503» — 503. */
    private final Map<String, String> answers = new HashMap<>();
    /** Строки всех запросов к заглушке без кодирования (первым клиент спрашивает robots.txt — строка пустая). */
    private final List<String> queries = new ArrayList<>();

    private HttpServer server;
    private OracleCloudAdapter adapter;

    /**
     * @throws IOException порт не открылся или образец не прочитан
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            queries.add(query == null ? "" : query);
            Matcher offset = OFFSET.matcher(query == null ? "" : query);
            String body = answers.get(exchange.getRequestURI().getPath() + "|" + (offset.find() ? offset.group(1) : ""));
            int status = body == null ? 404 : "503".equals(body) ? 503 : 200;
            byte[] bytes = status == 200 ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        adapter = new OracleCloudAdapter(httpClient, new OracleCloudProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{host}", 40));
        answers.put(path(GARRETT, LIST) + "|0", sample("oracle-garrett-sk.json"));
        answers.put(path(EMERSON, LIST) + "|0", sample("oracle-emerson-sk.json"));
        answers.put(path(VERTIV, DETAIL) + "|", sample("oracle-vertiv-job.json"));
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
     * Garrett Motion: запрос с номером сайта и страной по-английски, 7 вакансий Словакии, чтение полное; место —
     * {@code PrimaryLocation}, адрес — страница вакансии сайта.
     */
    @Test
    void readsCountryJobs() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(GARRETT, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).hasSize(7);
        assertThat(read.postings().get(0)).isEqualTo(new FetchedPosting("15023", "Shift Process Technician APU ZET",
                base() + "/ehth.fa.em2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_2001/job/15023",
                "ZÁBORSKÉ, PREŠOV, Slovakia", null));
        assertThat(read.postedAt()).containsEntry("15023", Instant.parse("2026-09-16T00:00:00Z"));
        assertThat(queries).anyMatch(query ->
                query.contains("finder=findReqs;siteNumber=CX_2001,location=Slovakia,limit=25,offset=0"));
    }

    /**
     * Emerson: вакансия с основным местом в Германии и дополнительным в Словакии входит.
     */
    @Test
    void secondaryLocationCountsAsCountry() {
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(EMERSON, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).extracting(FetchedPosting::externalId)
                .containsExactly("26012214", "26011670", "26011716", "26011718");
    }

    /**
     * Две страницы (объявлено 3): вакансия Польши без места в Словакии не входит; чтение полное.
     */
    @Test
    void readsPagesAndKeepsOnlyCountry() {
        answers.put(path(SYNTHETIC, LIST) + "|0", list(3, requisition("1", "SK"), requisition("2", "PL")));
        answers.put(path(SYNTHETIC, LIST) + "|2", list(3, requisition("3", "SK")));

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(SYNTHETIC, "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).extracting(FetchedPosting::externalId).containsExactly("1", "3");
    }

    /**
     * Отказ на второй странице — неполное чтение с прочитанным; на первой или ответ не список — источник недоступен.
     */
    @Test
    void failuresGivePartialOrUnavailable() {
        answers.put(path(SYNTHETIC, LIST) + "|0", list(3, requisition("1", "SK"), requisition("2", "SK")));
        answers.put(path(SYNTHETIC, LIST) + "|2", "503");
        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(SYNTHETIC, "SK");
        assertThat(read.partialReason()).isEqualTo(PartialReason.PAGE_FAILED);
        assertThat(read.postings()).hasSize(2);

        answers.put(path(SYNTHETIC, LIST) + "|0", "{\"title\":\"Not Found\"}");
        assertThat(adapter.read(SYNTHETIC, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
        answers.put(path(SYNTHETIC, LIST) + "|0", "503");
        assertThat(adapter.read(SYNTHETIC, "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
        assertThat(adapter.read("example.com/CX", "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
    }

    /**
     * Текст — описание вакансии из её детали; недопустимый id — без запроса.
     */
    @Test
    void readsJobDescription() {
        FetchedPosting detail = adapter.detail(VERTIV, "20262761");

        assertThat(detail.content()).contains("Position Summary/Objectives of Incumbent");
        assertThat(detail.url()).endsWith("/egup.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX/job/20262761");
        assertThat(adapter.detail(VERTIV, "1\" OR 1=1")).isNull();
    }

    private static String list(int total, String... requisitions) {
        return "{\"items\":[{\"TotalJobsCount\":" + total + ",\"requisitionList\":[" + String.join(",", requisitions)
                + "]}]}";
    }

    private static String requisition(String id, String country) {
        return "{\"Id\":\"" + id + "\",\"Title\":\"Job " + id + "\",\"PrimaryLocation\":\"City, " + country
                + "\",\"PrimaryLocationCountry\":\"" + country + "\",\"secondaryLocations\":[]}";
    }

    private static String path(String board, String api) {
        return "/" + board.substring(0, board.indexOf('/')) + api;
    }

    private static String sample(String name) throws IOException {
        return Files.readString(SAMPLES.resolve(name), StandardCharsets.UTF_8);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
