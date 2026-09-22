package com.roleorienta.worker.discovery.ct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roleorienta.worker.http.AddressPolicy;
import com.roleorienta.worker.http.SourceHttpClient;
import com.roleorienta.worker.http.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CertSpotterClient} против заглушки Cert Spotter API (локальный {@link HttpServer}).
 * Проверяются: разбор {@code dns_names}, отбрасывание апекса и wildcard, отбор только
 * хостов под доменом, и пагинация через {@code after} (страница 2 — пустая → стоп).
 * Permissive {@link AddressPolicy} — только чтобы поднять loopback-сервер.
 */
class CertSpotterClientTest {

    private HttpServer server;
    private CertSpotterClient client;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/issuances", exchange -> {
            drain(exchange.getRequestBody());
            requests.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            String body;
            if (query != null && query.contains("after=a2")) {
                body = "[]"; // вторая страница пуста → перечисление завершается
            } else {
                body = """
                        [
                          {"id":"a1","dns_names":["acme.wd5.myworkdayjobs.com","myworkdayjobs.com"]},
                          {"id":"a2","dns_names":["*.wd5.myworkdayjobs.com","globex.wd3.myworkdayjobs.com","unrelated.example.com"]}
                        ]""";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        SourceHttpClient http = new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)), 1000, 1000, 5);
        client = new CertSpotterClient(http, "http://127.0.0.1:" + port, 10);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void listsConcreteSubdomainsExcludingApexAndWildcard() {
        List<String> hosts = client.subdomainsOf("myworkdayjobs.com");

        assertEquals(
                List.of("acme.wd5.myworkdayjobs.com", "globex.wd3.myworkdayjobs.com"),
                hosts,
                "апекс, wildcard и чужой домен отброшены, порядок обнаружения сохранён");
    }

    @Test
    void paginatesUntilEmptyPage() {
        client.subdomainsOf("myworkdayjobs.com");
        // Страница 1 (без after) + страница 2 (after=a2, пустая) = минимум 2 запроса.
        assertTrue(requests.get() >= 2, "ожидалась пагинация, запросов: " + requests.get());
    }

    private static void drain(InputStream in) throws java.io.IOException {
        in.readAllBytes();
        in.close();
    }
}
