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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int STATUS_TEMPORARY_REDIRECT = 307;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;
    private static final String USER_AGENT = "RoleorientaTest/1.0 (+https://example.com)";
    private static final HostBudget NO_WAIT = host -> Optional.of(Duration.ZERO);

    private HttpServer server;
    private ExternalHttpClient client;

    /**
     * Локальный сервер на свободном порту и клиент, которому разрешены внутренние адреса.
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        client = client(properties(true), NO_WAIT);
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
     * После переадресации ответ несёт конечный адрес (аудит §65: страница разбирается от него — относительные ссылки и
     * хост доски SuccessFactors); без переадресации адреса нет.
     */
    @Test
    void reportsFinalLocationAfterRedirect() {
        respond("/careers", STATUS_OK, "text/plain", "jobs");
        server.createContext("/kariera", exchange -> {
            exchange.getResponseHeaders().set("Location", "/careers");
            send(exchange, STATUS_FOUND, new byte[0]);
        });

        HttpResult.Success redirected = (HttpResult.Success) client.get(uri("/kariera"));

        assertThat(redirected.body()).isEqualTo("jobs");
        assertThat(redirected.location()).isEqualTo(uri("/careers"));
        assertThat(redirected.locationOr(uri("/kariera"))).isEqualTo(uri("/careers"));
        assertThat(((HttpResult.Success) client.get(uri("/careers"))).location()).isNull();
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
     * Переадресации клиент проходит сам (аудит §66): 307 повторяет POST с тем же телом, 302 после POST — запрос
     * {@code GET} без тела (RFC 9110 §15.4).
     */
    @Test
    void redirect307KeepsPostAnd302TurnsIntoGet() {
        server.createContext("/echo", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            send(exchange, STATUS_OK, (exchange.getRequestMethod() + " " + new String(requestBody,
                    StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/temporary", exchange -> {
            exchange.getResponseHeaders().set("Location", "/echo");
            send(exchange, STATUS_TEMPORARY_REDIRECT, new byte[0]);
        });
        server.createContext("/found", exchange -> {
            exchange.getResponseHeaders().set("Location", "/echo");
            send(exchange, STATUS_FOUND, new byte[0]);
        });

        assertThat(((HttpResult.Success) client.postJson(uri("/temporary"), "{\"page\":2}")).body())
                .isEqualTo("POST {\"page\":2}");
        assertThat(((HttpResult.Success) client.postJson(uri("/found"), "{\"page\":2}")).body())
                .isEqualTo("GET ");
    }

    /**
     * Относительная {@code Location} без {@code /} от адреса без пути ({@code http://хост}) разрешается от корня
     * сайта: {@code URI.resolve} склеил бы хост и путь (аудит §66).
     */
    @Test
    void relativeRedirectFromAddressWithoutPath() {
        respond("/careers", STATUS_OK, "text/plain", "jobs");
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "careers");
            send(exchange, STATUS_FOUND, new byte[0]);
        });

        HttpResult.Success result = (HttpResult.Success) client.get(URI.create("http://127.0.0.1:" + port()));

        assertThat(result.body()).isEqualTo("jobs");
        assertThat(result.location()).isEqualTo(uri("/careers"));
    }

    /**
     * Каждый шаг переадресации занимает место в очереди своего хоста: robots.txt, первый запрос, цель (аудит §66).
     * Порт вне 1–65535 в {@code Location} — постоянный отказ без исключения.
     */
    @Test
    void everyRedirectHopReservesHostBudget() throws IOException {
        List<String> reserved = new java.util.ArrayList<>();
        respond("/careers", STATUS_OK, "text/plain", "jobs");
        server.createContext("/kariera", exchange -> {
            exchange.getResponseHeaders().set("Location", "/careers");
            send(exchange, STATUS_FOUND, new byte[0]);
        });
        server.createContext("/bad-port", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:99999/");
            send(exchange, STATUS_FOUND, new byte[0]);
        });

        try (ExternalHttpClient counting = client(properties(true), host -> {
            reserved.add(host);
            return Optional.of(Duration.ZERO);
        })) {
            assertThat(counting.get(uri("/kariera"))).isInstanceOf(HttpResult.Success.class);
            assertThat(reserved).hasSize(3);
            assertThat(kind(counting.get(uri("/bad-port")))).isEqualTo(HttpResult.Kind.BLOCKED);
        }
    }

    /**
     * Переадресация на другое происхождение сверяется с robots.txt цели: запрещённый там путь не запрашивается.
     */
    @Test
    void redirectToOtherOriginChecksItsRobots() throws IOException {
        HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger requests = new AtomicInteger();
        other.createContext("/robots.txt", exchange ->
                send(exchange, STATUS_OK, "User-agent: *\nDisallow: /private\n".getBytes(StandardCharsets.UTF_8)));
        other.createContext("/private", exchange -> {
            requests.incrementAndGet();
            send(exchange, STATUS_OK, new byte[0]);
        });
        other.start();
        try {
            server.createContext("/open", exchange -> {
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + other.getAddress().getPort() + "/private/1");
                send(exchange, STATUS_FOUND, new byte[0]);
            });

            assertThat(kind(client.get(uri("/open")))).isEqualTo(HttpResult.Kind.USE_FORBIDDEN);
            assertThat(requests.get()).isZero();
        } finally {
            other.stop(0);
        }
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
        try (ExternalHttpClient strict = client(properties(false), NO_WAIT)) {
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

    /**
     * Каждый запрос несёт единый User-Agent.
     */
    @Test
    void sendsUserAgent() {
        server.createContext("/agent", exchange -> send(exchange, STATUS_OK,
                exchange.getRequestHeaders().getFirst("User-Agent").getBytes(StandardCharsets.UTF_8)));

        assertThat(client.get(uri("/agent"))).isEqualTo(new HttpResult.Success(STATUS_OK, USER_AGENT));
    }

    /**
     * Очередь к хосту длиннее потолка — временный отказ, запрос не отправляется.
     */
    @Test
    void exhaustedHostBudgetSkipsRequest() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/counted", exchange -> {
            requests.incrementAndGet();
            send(exchange, STATUS_OK, new byte[0]);
        });

        try (ExternalHttpClient limited = client(properties(true), host -> Optional.empty())) {
            assertThat(limited.get(uri("/counted"))).isInstanceOf(HttpResult.TemporaryFailure.class);
        }
        assertThat(requests.get()).isZero();
    }

    /**
     * {@code Retry-After} ответа сдвигает очередь хоста.
     */
    @Test
    void retryAfterBacksOffHost() throws IOException {
        server.createContext("/busy", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "120");
            send(exchange, STATUS_TOO_MANY_REQUESTS, new byte[0]);
        });
        Map<String, Duration> backOffs = new HashMap<>();
        HostBudget recording = new HostBudget() {
            @Override
            public Optional<Duration> reserve(String host) {
                return Optional.of(Duration.ZERO);
            }

            @Override
            public void backOff(String host, Duration delay) {
                backOffs.put(host, delay);
            }
        };

        try (ExternalHttpClient budgeted = client(properties(true), recording)) {
            budgeted.get(uri("/busy"));
        }
        assertThat(backOffs).containsEntry("127.0.0.1", Duration.ofSeconds(120));
    }

    /**
     * Переадресация на путь, запрещённый robots.txt, — постоянный отказ без запроса цели: шаг переадресации
     * сверяется с robots.txt так же, как первый запрос (аудит §66).
     */
    @Test
    void robotsDisallowAppliesToRedirectTarget() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/robots.txt", exchange ->
                send(exchange, STATUS_OK, "User-agent: *\nDisallow: /private\n".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/private", exchange -> {
            requests.incrementAndGet();
            send(exchange, STATUS_OK, new byte[0]);
        });
        server.createContext("/open", exchange -> {
            exchange.getResponseHeaders().set("Location", "/private/1");
            send(exchange, STATUS_FOUND, new byte[0]);
        });

        assertThat(kind(client.get(uri("/open")))).isEqualTo(HttpResult.Kind.USE_FORBIDDEN);
        assertThat(requests.get()).isZero();
    }

    /**
     * Путь запрещён robots.txt — постоянный отказ без запроса; robots.txt читается один раз.
     */
    @Test
    void robotsDisallowForbidsRequest() {
        AtomicInteger robotsReads = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/robots.txt", exchange -> {
            robotsReads.incrementAndGet();
            send(exchange, STATUS_OK, "User-agent: *\nDisallow: /private\n".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/private", exchange -> {
            requests.incrementAndGet();
            send(exchange, STATUS_OK, new byte[0]);
        });
        respond("/public", STATUS_OK, "text/plain", "ok");

        assertThat(kind(client.get(uri("/private/1")))).isEqualTo(HttpResult.Kind.USE_FORBIDDEN);
        assertThat(client.get(uri("/public"))).isEqualTo(new HttpResult.Success(STATUS_OK, "ok"));
        assertThat(requests.get()).isZero();
        assertThat(robotsReads.get()).isEqualTo(1);
    }

    /**
     * Хост из списка исключений (решение владельца) — robots.txt не читается, запрос выполняется.
     */
    @Test
    void robotsExemptHostIgnoresRobots() throws IOException {
        AtomicInteger robotsReads = new AtomicInteger();
        server.createContext("/robots.txt", exchange -> {
            robotsReads.incrementAndGet();
            send(exchange, STATUS_OK, "User-agent: *\nDisallow: /\n".getBytes(StandardCharsets.UTF_8));
        });
        respond("/sparql", STATUS_OK, "text/plain", "ok");
        try (ExternalHttpClient exempt = new ExternalHttpClient(properties(true),
                new PolitenessProperties(USER_AGENT, Duration.ZERO, Duration.ZERO, List.of("127.0.0.1")), NO_WAIT,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThat(exempt.get(uri("/sparql"))).isEqualTo(new HttpResult.Success(STATUS_OK, "ok"));
        }
        assertThat(robotsReads.get()).isZero();
    }

    /**
     * Домена нет (зона {@code .invalid} не разрешается никогда, RFC 2606) — постоянный отказ {@code NO_SUCH_HOST},
     * а не временный: повтор не поможет.
     */
    @Test
    void unknownHostIsPermanentFailure() {
        assertThat(client.get(URI.create("http://no-such-host.invalid/"))).isInstanceOfSatisfying(
                HttpResult.PermanentFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(HttpResult.Kind.NO_SUCH_HOST));
    }

    /**
     * robots.txt временно недоступен (5xx) — запрос откладывается, а не выполняется.
     */
    @Test
    void unavailableRobotsDefersRequest() {
        respond("/robots.txt", STATUS_SERVICE_UNAVAILABLE, "text/plain", "");
        respond("/ok", STATUS_OK, "text/plain", "ok");

        assertThat(client.get(uri("/ok"))).isInstanceOf(HttpResult.TemporaryFailure.class);
    }

    private static ExternalHttpClient client(ExternalHttpProperties properties, HostBudget budget) {
        return new ExternalHttpClient(properties,
                new PolitenessProperties(USER_AGENT, Duration.ZERO, Duration.ZERO, List.of()), budget,
                Clock.fixed(NOW, ZoneOffset.UTC));
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
