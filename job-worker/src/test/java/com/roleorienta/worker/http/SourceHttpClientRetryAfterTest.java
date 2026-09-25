package com.roleorienta.worker.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * {@link SourceHttpClient} + {@link RequestPacer} (§57) против локального {@link HttpServer}:
 * {@code 429 Retry-After: 120} ставит домен на паузу — следующий запрос не уходит в сеть,
 * а отклоняется {@link SourceBackoffException}; {@code 503} без заголовка паузы не ставит;
 * разбор {@code Retry-After} в секундах и HTTP-дате. Permissive {@link AddressPolicy} —
 * только для loopback.
 */
class SourceHttpClientRetryAfterTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/limited", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Retry-After", "120");
            reply(ex, 429);
        });
        server.createContext("/down", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            reply(ex, 503);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private SourceHttpClient client() {
        SourcePacingProperties props = new SourcePacingProperties(0, Map.of(), 5_000, 3_600_000, 60_000);
        return new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)),
                new SourceHttpProperties(1000, 1000, 5, SourceHttpClient.DEFAULT_MAX_BODY_BYTES),
                new RequestPacer(props), props);
    }

    @Test
    void retryAfterBlocksFollowingRequestsWithoutHittingSource() {
        SourceHttpClient client = client();

        assertThrows(HttpClientErrorException.TooManyRequests.class, () -> client.getBody(base + "/limited"));
        SourceBackoffException backoff = assertThrows(SourceBackoffException.class, () -> client.getBody(base + "/down"));

        assertEquals(1, hits.get(), "второй запрос к тому же домену в сеть не ушёл");
        assertTrue(backoff.getWait().toSeconds() > 100, "пауза ~120 с: " + backoff.getWait());
    }

    @Test
    void unavailableWithoutRetryAfterDoesNotPause() {
        SourceHttpClient client = client();

        assertThrows(HttpServerErrorException.class, () -> client.getBody(base + "/down"));
        assertThrows(HttpServerErrorException.class, () -> client.getBody(base + "/down"));

        assertEquals(2, hits.get());
    }

    @Test
    void retryAfterHeaderFormats() {
        SourceHttpClient client = client();
        HttpClientErrorException withSeconds = HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "", headers("30"), null, null);
        HttpClientErrorException withoutHeader = HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "", headers(null), null, null);
        HttpClientErrorException notFound = HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "", headers("30"), null, null);
        String inTenMinutes = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(10));
        HttpClientErrorException withDate = HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "", headers(inTenMinutes), null, null);

        assertEquals(Duration.ofSeconds(30), client.retryAfter(withSeconds).orElseThrow());
        assertEquals(Duration.ofMinutes(1), client.retryAfter(withoutHeader).orElseThrow(), "429 без заголовка");
        assertTrue(client.retryAfter(notFound).isEmpty());
        long dateSeconds = client.retryAfter(withDate).orElseThrow().toSeconds();
        assertTrue(dateSeconds > 590 && dateSeconds <= 600, "HTTP-дата: " + dateSeconds);
    }

    private static org.springframework.http.HttpHeaders headers(String retryAfter) {
        org.springframework.http.HttpHeaders httpHeaders = new org.springframework.http.HttpHeaders();
        if (retryAfter != null) {
            httpHeaders.add("Retry-After", retryAfter);
        }
        return httpHeaders;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status) throws java.io.IOException {
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
