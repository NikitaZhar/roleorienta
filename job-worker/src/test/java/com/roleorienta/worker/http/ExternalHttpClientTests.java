package com.roleorienta.worker.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Внешний HTTP-клиент на локальном сервере JDK: разбор ответов, потолок тела, редиректы,
 * таймаут, защита от SSRF.
 */
class ExternalHttpClientTests {

    private static final int MAX_BODY_BYTES = 64;
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final long SLOW_RESPONSE_MILLIS = 2000;
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final int STATUS_OK = 200;
    private static final int STATUS_FOUND = 302;

    private HttpServer server;
    private ExternalHttpClient client;

    /**
     * Локальный сервер на свободном порту и клиент, которому разрешены внутренние адреса.
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        client = new ExternalHttpClient(properties(true), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Остановка сервера и клиента.
     */
    @AfterEach
    void stop() throws IOException {
        client.close();
        server.stop(0);
    }

    /**
     * 200 — тело в кодировке из Content-Type.
     */
    @Test
    void returnsBodyOnSuccess() {
        respond("/ok", STATUS_OK, "text/plain; charset=UTF-8", "Вакансии");

        assertThat(client.get(uri("/ok"))).isEqualTo(new HttpResult.Success(STATUS_OK, "Вакансии"));
    }

    /**
     * POST отправляет JSON-тело; сервер возвращает его обратно.
     */
    @Test
    void postsJsonBody() {
        server.createContext("/echo", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", exchange.getRequestHeaders().getFirst("Content-Type"));
            send(exchange, STATUS_OK, requestBody);
        });

        HttpResult result = client.postJson(uri("/echo"), "{\"limit\":20}");

        assertThat(result).isEqualTo(new HttpResult.Success(STATUS_OK, "{\"limit\":20}"));
    }

    /**
     * 503 и 429 — временный отказ; {@code Retry-After} в секундах и в форме даты.
     */
    @Test
    void mapsServerErrorsAndRateLimitToTemporaryFailure() {
        respond("/down", 503, "text/plain", "");
        server.createContext("/limited", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "120");
            send(exchange, 429, new byte[0]);
        });
        server.createContext("/limited-until", exchange -> {
            exchange.getResponseHeaders().set("Retry-After",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(60).atZone(ZoneOffset.UTC)));
            send(exchange, 429, new byte[0]);
        });

        assertThat(client.get(uri("/down"))).isEqualTo(new HttpResult.TemporaryFailure("HTTP 503", Duration.ZERO));
        assertThat(client.get(uri("/limited")))
                .isEqualTo(new HttpResult.TemporaryFailure("HTTP 429", Duration.ofSeconds(120)));
        assertThat(client.get(uri("/limited-until")))
                .isEqualTo(new HttpResult.TemporaryFailure("HTTP 429", Duration.ofSeconds(60)));
    }

    /**
     * 403, 404, 400 — постоянные отказы своего вида.
     */
    @Test
    void mapsClientErrorsToPermanentFailure() {
        respond("/forbidden", 403, "text/plain", "");
        respond("/missing", 404, "text/plain", "");
        respond("/bad", 400, "text/plain", "");

        assertThat(kind(client.get(uri("/forbidden")))).isEqualTo(HttpResult.Kind.ACCESS_DENIED);
        assertThat(kind(client.get(uri("/missing")))).isEqualTo(HttpResult.Kind.NOT_FOUND);
        assertThat(kind(client.get(uri("/bad")))).isEqualTo(HttpResult.Kind.CLIENT_ERROR);
    }

    /**
     * Тело больше потолка — отказ без чтения остатка.
     */
    @Test
    void rejectsTooLargeBody() {
        respond("/big", STATUS_OK, "text/plain", "x".repeat(MAX_BODY_BYTES + 1));

        assertThat(kind(client.get(uri("/big")))).isEqualTo(HttpResult.Kind.TOO_LARGE);
    }

    /**
     * Редирект сам на себя — постоянный отказ, а не бесконечный цикл.
     */
    @Test
    void stopsRedirectLoop() {
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop");
            send(exchange, STATUS_FOUND, new byte[0]);
        });

        assertThat(kind(client.get(uri("/loop")))).isEqualTo(HttpResult.Kind.CLIENT_ERROR);
    }

    /**
     * Ответ дольше таймаута чтения — временный отказ.
     */
    @Test
    void mapsTimeoutToTemporaryFailure() {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(SLOW_RESPONSE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            send(exchange, STATUS_OK, new byte[0]);
        });

        assertThat(client.get(uri("/slow"))).isInstanceOf(HttpResult.TemporaryFailure.class);
    }

    /**
     * Со строгой политикой: внутренние адреса, облачный metadata-адрес и не-http схемы
     * заблокированы до соединения.
     */
    @Test
    void blocksInternalAddressesAndForeignSchemes() throws IOException {
        respond("/ok", STATUS_OK, "text/plain", "ok");
        try (ExternalHttpClient strict = new ExternalHttpClient(properties(false), Clock.systemUTC())) {
            assertThat(kind(strict.get(uri("/ok")))).isEqualTo(HttpResult.Kind.BLOCKED);
            assertThat(kind(strict.get(URI.create("http://localhost:" + port() + "/ok"))))
                    .isEqualTo(HttpResult.Kind.BLOCKED);
            assertThat(kind(strict.get(URI.create("http://169.254.169.254/latest/meta-data/"))))
                    .isEqualTo(HttpResult.Kind.BLOCKED);
            assertThat(kind(strict.get(URI.create("ftp://example.com/file")))).isEqualTo(HttpResult.Kind.BLOCKED);
        }
    }

    private static ExternalHttpProperties properties(boolean allowPrivateAddresses) {
        return new ExternalHttpProperties(Duration.ofSeconds(2), READ_TIMEOUT, 3, MAX_BODY_BYTES,
                allowPrivateAddresses);
    }

    private void respond(String path, int status, String contentType, String body) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
        });
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port() + path);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static HttpResult.Kind kind(HttpResult result) {
        assertThat(result).isInstanceOf(HttpResult.PermanentFailure.class);
        return ((HttpResult.PermanentFailure) result).kind();
    }
}
