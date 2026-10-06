package com.roleorienta.worker.adapter.nalgoo;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.SourceReadResult;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер Nalgoo на настоящих ответах API ({@code docs/samples/nalgoo-*.json}, сняты 2026-10-06): список BILLA (133
 * вакансии), список Foxconn (8), вакансия BILLA с текстом.
 */
class NalgooAdapterTests {

    private static final Path SAMPLES = Path.of("../docs/samples");

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /** Тело по пути; нет — 404; {@code "503"} — 503. */
    private final Map<String, String> answers = new HashMap<>();

    private HttpServer server;
    private NalgooAdapter adapter;

    /**
     * @throws IOException порт не открылся или образец не прочитан
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = answers.get(exchange.getRequestURI().getPath());
            int status = body == null ? 404 : "503".equals(body) ? 503 : 200;
            byte[] bytes = status == 200 ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        adapter = new NalgooAdapter(httpClient, new NalgooProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3"));
        answers.put("/api/v3/organizations/billa/jobs", sample("nalgoo-billa-jobs.json"));
        answers.put("/api/v3/organizations/foxconn/jobs", sample("nalgoo-foxconn-jobs.json"));
        answers.put("/api/v3/organizations/billa/jobs/83976", sample("nalgoo-billa-job.json"));
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
     * Весь список одним ответом: id, позиция, ссылка, место (город); чтение полное.
     */
    @Test
    void readsWholeBoard() {
        SourceReadResult.Read billa = (SourceReadResult.Read) adapter.read("billa");
        assertThat(billa.complete()).isTrue();
        assertThat(billa.postings()).hasSize(133);
        assertThat(billa.postings().get(0)).isEqualTo(new FetchedPosting("93165",
                "Brigádnik (ž/m) - Sládkovičovo - práca vhodná pre študenta", "https://billa.nalgoo-jobs.com/jobs/93165",
                "Sládkovičovo", null));

        SourceReadResult.Read foxconn = (SourceReadResult.Read) adapter.read("foxconn");
        assertThat(foxconn.postings()).hasSize(8).allMatch(posting -> "Nitra".equals(posting.location()));
    }

    /**
     * Текст — поле {@code content} вакансии.
     */
    @Test
    void readsJobContent() {
        FetchedPosting detail = adapter.detail("billa", "83976");

        assertThat(detail.content()).contains("Popis pracovného miesta");
        assertThat(detail.location()).isEqualTo("Sereď - Šulekovská ul.");
        assertThat(adapter.detail("billa", "not-an-id")).isNull();
    }

    /**
     * Отказ, неразобранный ответ и чужое имя доски — источник недоступен.
     */
    @Test
    void failuresAreUnavailable() {
        answers.put("/api/v3/organizations/billa/jobs", "503");
        assertThat(adapter.read("billa")).isInstanceOf(SourceReadResult.Unavailable.class);
        answers.put("/api/v3/organizations/billa/jobs", "{\"error\": 1}");
        assertThat(adapter.read("billa")).isInstanceOf(SourceReadResult.Unavailable.class);
        assertThat(adapter.read("Billa Slovakia")).isInstanceOf(SourceReadResult.Unavailable.class);
    }

    private static String sample(String name) throws IOException {
        return Files.readString(SAMPLES.resolve(name), StandardCharsets.UTF_8);
    }
}
