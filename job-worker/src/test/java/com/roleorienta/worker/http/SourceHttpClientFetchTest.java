package com.roleorienta.worker.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Happy-path {@link SourceHttpClient}: под permissive-политикой (разрешён loopback)
 * клиент читает тело с локального сервера и следует за редиректом — каждый переход
 * проходит тот же резолвер (ре-валидация, A13). Строгая политика loopback запрещает
 * (см. {@link SourceHttpClientSsrfTest}); permissive нужна только чтобы поднять
 * локальный сервер в тесте. Потолок размера тела (B2, §71): тело ровно на пределе читается,
 * больше предела — {@link SourceHttpClient.ResponseTooLargeException}.
 */
class SourceHttpClientFetchTest {

    private HttpServer server;
    private int port;
    private SourceHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/jobs", exchange -> {
            byte[] body = "OK-BODY".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/redir", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/jobs");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        client = new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)), 1000, 1000, 5);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void readsBody() {
        assertEquals("OK-BODY", client.getBody("http://127.0.0.1:" + port + "/jobs"));
    }

    @Test
    void followsRedirect() {
        assertEquals("OK-BODY", client.getBody("http://127.0.0.1:" + port + "/redir"));
    }

    @Test
    void bodyAtLimitIsReadAndLargerIsRejected() {
        // «OK-BODY» — 7 байт: при потолке 7 читается, при потолке 6 — отклоняется.
        String url = "http://127.0.0.1:" + port + "/jobs";
        assertEquals("OK-BODY", limitedClient(7).getBody(url));
        assertThrows(SourceHttpClient.ResponseTooLargeException.class, () -> limitedClient(6).getBody(url));
    }

    private SourceHttpClient limitedClient(int maxBodyBytes) {
        return new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)),
                new SourceHttpProperties(1000, 1000, 5, maxBodyBytes),
                RequestPacer.unpaced(), new SourcePacingProperties(0, Map.of(), Long.MAX_VALUE, 0, 0));
    }
}
