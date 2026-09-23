package com.roleorienta.worker.discovery.cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roleorienta.worker.http.AddressPolicy;
import com.roleorienta.worker.http.SourceHttpClient;
import com.roleorienta.worker.http.SsrfGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

/**
 * {@link CommonCrawlIndexClient} против заглушки CDX-сервера (локальный {@link HttpServer}):
 * свежайшая коллекция из {@code collinfo.json}, число страниц ({@code showNumPages}),
 * разбор JSON Lines страницы, {@code 404} индекса = пусто, 5xx пробрасывается,
 * кодирование шаблона в запросе. Permissive {@link AddressPolicy} — только для loopback.
 */
class CommonCrawlIndexClientTest {

    private HttpServer server;
    private CommonCrawlIndexClient client;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collinfo.json", ex -> respond(ex, 200, """
                [{"id":"CC-MAIN-2026-39","name":"September 2026 Index"},
                 {"id":"CC-MAIN-2026-33","name":"August 2026 Index"}]"""));
        server.createContext("/CC-MAIN-2026-39-index", ex -> {
            String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            lastQuery.set(query);
            if (query.contains("url=*.nothing.example")) {
                respond(ex, 404, "{\"message\": \"No Captures found for: *.nothing.example\"}");
            } else if (query.contains("showNumPages=true")) {
                respond(ex, 200, "{\"pages\": 7, \"pageSize\": 5, \"blocks\": 33}");
            } else {
                respond(ex, 200, """
                        {"url": "https://amgen.wd1.myworkdayjobs.com/en-US/Careers/job/x"}
                        {"url": "https://3m.wd1.myworkdayjobs.com/Search"}

                        {"status": "200"}
                        """);
            }
        });
        server.createContext("/CC-MAIN-BROKEN-index", ex -> respond(ex, 503, "overloaded"));
        server.start();
        SourceHttpClient http = new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)), 1000, 1000, 5);
        client = new CommonCrawlIndexClient(http, "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void latestCollectionIsFirst() {
        assertEquals("CC-MAIN-2026-39", client.latestCollection());
    }

    @Test
    void pageCountFromShowNumPages() {
        assertEquals(7, client.pageCount("CC-MAIN-2026-39", "*.myworkdayjobs.com"));
        assertTrue(lastQuery.get().contains("url=*.myworkdayjobs.com"), lastQuery.get());
    }

    @Test
    void urlsOnPageParsesJsonLinesAndSkipsBlankOrForeignLines() {
        List<String> urls = client.urlsOnPage("CC-MAIN-2026-39", "*.myworkdayjobs.com", 3);

        assertEquals(List.of(
                "https://amgen.wd1.myworkdayjobs.com/en-US/Careers/job/x",
                "https://3m.wd1.myworkdayjobs.com/Search"), urls);
        assertTrue(lastQuery.get().contains("fl=url") && lastQuery.get().contains("page=3"), lastQuery.get());
    }

    @Test
    void noCapturesIsEmptyNotError() {
        assertEquals(0, client.pageCount("CC-MAIN-2026-39", "*.nothing.example"));
        assertTrue(client.urlsOnPage("CC-MAIN-2026-39", "*.nothing.example", 0).isEmpty());
    }

    @Test
    void serverErrorPropagates() {
        assertThrows(HttpServerErrorException.class,
                () -> client.urlsOnPage("CC-MAIN-BROKEN", "*.myworkdayjobs.com", 0));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        ex.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
