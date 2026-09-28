package com.roleorienta.worker.adapter.personio;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.ExternalHttpProperties;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер Personio на заглушке ленты {@code /<board>/xml}: разбор, не-XML ответ, отказ.
 */
class PersonioAdapterTests {

    private static final String BOARD = "acme";
    private static final int MAX_BODY_BYTES = 1_000_000;

    private final ExternalHttpClient httpClient = new ExternalHttpClient(new ExternalHttpProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(2), 3, MAX_BODY_BYTES, true));

    private HttpServer server;
    private int status;
    private String body;
    private PersonioAdapter adapter;

    /**
     * Заглушка ленты на свободном порту.
     *
     * @throws IOException порт не открылся
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/" + BOARD + "/xml", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        adapter = new PersonioAdapter(httpClient, new PersonioProperties(baseUrl().replace(BOARD, "{board}")));
    }

    /**
     * Остановка заглушки и клиента.
     */
    @AfterEach
    void stop() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    /**
     * Поля публикации из ленты; тексты описания склеены; {@code office} вложенных
     * {@code additionalOffices} не подменяет основной.
     */
    @Test
    void parsesPositions() {
        status = 200;
        body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <workzag-jobs>
                  <position>
                    <id>101</id>
                    <office>Bratislava</office>
                    <additionalOffices><office>Vienna</office></additionalOffices>
                    <name>Java Developer</name>
                    <jobDescriptions>
                      <jobDescription><name>Intro</name><value><![CDATA[<p>Java</p>]]></value></jobDescription>
                      <jobDescription><name>Tasks</name><value><![CDATA[<p>Code</p>]]></value></jobDescription>
                    </jobDescriptions>
                  </position>
                  <position><id>102</id><name>QA Engineer</name></position>
                </workzag-jobs>
                """;

        assertThat(adapter.read(BOARD)).isEqualTo(new SourceReadResult.Read(List.of(
                new FetchedPosting("101", "Java Developer", baseUrl() + "/job/101", "Bratislava",
                        "<p>Java</p>\n<p>Code</p>"),
                new FetchedPosting("102", "QA Engineer", baseUrl() + "/job/102", null, null)), true));
    }

    /**
     * Вместо ленты пришла HTML-страница — временный отказ, а не пустой список.
     */
    @Test
    void reportsNonFeedAsUnavailable() {
        status = 200;
        body = "<html><body>Maintenance</body></html>";

        assertThat(adapter.read(BOARD)).isInstanceOf(SourceReadResult.Unavailable.class);
    }

    /**
     * 503 — источник временно недоступен.
     */
    @Test
    void reportsUnavailableSource() {
        status = 503;
        body = "";

        assertThat(adapter.read(BOARD)).isEqualTo(new SourceReadResult.Unavailable(
                new HttpResult.TemporaryFailure("HTTP 503", Duration.ZERO)));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + BOARD;
    }
}
