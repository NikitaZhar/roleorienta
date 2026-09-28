package com.roleorienta.worker.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * Единственный путь внешних HTTP-запросов worker (технический документ §10): реестры, индексы,
 * сайты компаний, доски систем найма.
 *
 * <p>Защита от SSRF: разрешены только http/https; имя хоста определяется подменённым
 * {@link SystemDefaultDnsResolver}, который проверяет каждый полученный адрес по
 * {@link AddressPolicy}. Соединение идёт ровно на проверенный адрес — в том числе на каждом
 * редиректе, поэтому подмена DNS-ответа между проверкой и соединением (DNS-rebinding) не помогает.
 * https://hc.apache.org/httpcomponents-client-5.4.x/</p>
 *
 * <p>Перед запросом место в очереди к хосту резервирует {@link HostBudget} (общий для реплик
 * промежуток между запросами); запрос ждёт своего места, а если очередь длиннее потолка —
 * возвращается временный отказ без запроса. {@code Retry-After} ответа сдвигает очередь хоста.
 * Редирект на другой хост бюджет не резервирует. User-Agent — из {@link PolitenessProperties}.</p>
 *
 * <p>Путь сверяется с robots.txt происхождения ({@link RobotsRules}, кэш на сутки в памяти
 * реплики); запрет — постоянный отказ {@code USE_FORBIDDEN} без запроса (технический документ
 * §10). robots.txt соблюдается, но не считается разрешением на использование данных.</p>
 *
 * <p>Таймауты, потолок редиректов и тела ответа — из {@link ExternalHttpProperties}. Встроенные
 * повторы библиотеки выключены: она повторяла бы 429/503 и спала бы, держа поток; повторы —
 * забота таблицы заданий. Ответ сразу разобран в {@link HttpResult}.</p>
 */
@Component
public class ExternalHttpClient implements AutoCloseable {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final int STATUS_OK_MIN = 200;
    private static final int STATUS_OK_MAX = 299;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_GONE = 410;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int STATUS_SERVER_ERROR_MIN = 500;
    private static final String ROBOTS_PATH = "/robots.txt";
    /** RFC 9309 §2.4: кэш robots.txt — не дольше суток. */
    private static final Duration ROBOTS_TTL = Duration.ofHours(24);

    private final CloseableHttpClient httpClient;
    private final HostBudget hostBudget;
    private final int maxBodyBytes;
    private final Clock clock;
    private final String robotsToken;
    private final Map<String, CachedRobots> robotsByOrigin = new ConcurrentHashMap<>();

    /**
     * @param properties   таймауты, потолки и политика адресов
     * @param politeness   User-Agent
     * @param hostBudget   очередь запросов к хосту
     * @param clock        часы для разбора {@code Retry-After} в форме даты
     */
    public ExternalHttpClient(ExternalHttpProperties properties, PolitenessProperties politeness,
            HostBudget hostBudget, Clock clock) {
        this.httpClient = buildClient(properties, politeness.userAgent());
        this.hostBudget = hostBudget;
        this.maxBodyBytes = properties.maxBodyBytes();
        this.clock = clock;
        this.robotsToken = politeness.userAgent().split("/", 2)[0].strip();
    }

    /**
     * GET-запрос.
     *
     * @param uri адрес
     * @return разобранный результат; исключений не бросает
     */
    public HttpResult get(URI uri) {
        return execute(uri, new HttpGet(uri));
    }

    /**
     * POST-запрос с JSON-телом (например, список вакансий Workday).
     *
     * @param uri  адрес
     * @param json тело запроса
     * @return разобранный результат; исключений не бросает
     */
    public HttpResult postJson(URI uri, String json) {
        HttpPost request = new HttpPost(uri);
        request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        request.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        return execute(uri, request);
    }

    /**
     * Закрывает пул соединений; Spring вызывает при остановке контекста.
     *
     * @throws IOException при ошибке закрытия
     */
    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private HttpResult execute(URI uri, ClassicHttpRequest request) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme) || uri.getHost() == null) {
            return new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED, "Not an http(s) URL: " + uri);
        }
        Optional<HttpResult> robotsRefusal = robotsRefusal(uri);
        if (robotsRefusal.isPresent()) {
            return robotsRefusal.get();
        }
        return send(uri, request);
    }

    /**
     * robots.txt происхождения запроса (кэш на сутки): 2xx — правила; постоянный отказ (4xx) —
     * разрешено всё; временный отказ — запрос откладывается, в кэш не кладётся (RFC 9309 §2.3.1).
     */
    private Optional<HttpResult> robotsRefusal(URI uri) {
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
        CachedRobots cached = robotsByOrigin.get(origin);
        if (cached == null || cached.fetchedAt().plus(ROBOTS_TTL).isBefore(clock.instant())) {
            URI robotsUri = URI.create(origin + ROBOTS_PATH);
            RobotsRules rules;
            switch (send(robotsUri, new HttpGet(robotsUri))) {
                case HttpResult.Success success -> rules = RobotsRules.parse(success.body(), robotsToken);
                case HttpResult.PermanentFailure notFound -> rules = RobotsRules.ALLOW_ALL;
                case HttpResult.TemporaryFailure temporary -> {
                    return Optional.of(new HttpResult.TemporaryFailure(
                            "robots.txt unavailable: " + temporary.reason(), temporary.retryAfter()));
                }
            }
            cached = new CachedRobots(rules, clock.instant());
            robotsByOrigin.put(origin, cached);
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String pathAndQuery = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        return cached.rules().allows(pathAndQuery) ? Optional.empty() : Optional.of(new HttpResult.PermanentFailure(
                HttpResult.Kind.USE_FORBIDDEN, "Disallowed by robots.txt: " + uri));
    }

    private HttpResult send(URI uri, ClassicHttpRequest request) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Optional<Duration> wait = hostBudget.reserve(host);
        if (wait.isEmpty()) {
            return new HttpResult.TemporaryFailure("Host budget exhausted: " + host, Duration.ZERO);
        }
        try {
            Thread.sleep(wait.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new HttpResult.TemporaryFailure("Interrupted while waiting for host budget", Duration.ZERO);
        }
        HttpResult result;
        try {
            result = httpClient.execute(request, this::toResult);
        } catch (IOException exception) {
            result = fromException(exception);
        }
        if (result instanceof HttpResult.TemporaryFailure temporary && temporary.retryAfter().isPositive()) {
            hostBudget.backOff(host, temporary.retryAfter());
        }
        return result;
    }

    /**
     * @param rules     правила robots.txt
     * @param fetchedAt когда получены
     */
    private record CachedRobots(RobotsRules rules, Instant fetchedAt) {
    }

    private HttpResult toResult(ClassicHttpResponse response) throws IOException {
        int status = response.getCode();
        if (status >= STATUS_OK_MIN && status <= STATUS_OK_MAX) {
            return readBody(status, response.getEntity());
        }
        if (status == STATUS_TOO_MANY_REQUESTS || status >= STATUS_SERVER_ERROR_MIN) {
            return new HttpResult.TemporaryFailure("HTTP " + status,
                    retryAfter(response.getFirstHeader(HttpHeaders.RETRY_AFTER)));
        }
        if (status == STATUS_UNAUTHORIZED || status == STATUS_FORBIDDEN) {
            return new HttpResult.PermanentFailure(HttpResult.Kind.ACCESS_DENIED, "HTTP " + status);
        }
        if (status == STATUS_NOT_FOUND || status == STATUS_GONE) {
            return new HttpResult.PermanentFailure(HttpResult.Kind.NOT_FOUND, "HTTP " + status);
        }
        return new HttpResult.PermanentFailure(HttpResult.Kind.CLIENT_ERROR, "HTTP " + status);
    }

    private HttpResult readBody(int status, HttpEntity entity) throws IOException {
        if (entity == null) {
            return new HttpResult.Success(status, "");
        }
        byte[] body;
        try (InputStream stream = entity.getContent()) {
            body = stream.readNBytes(maxBodyBytes + 1);
        }
        if (body.length > maxBodyBytes) {
            return new HttpResult.PermanentFailure(HttpResult.Kind.TOO_LARGE,
                    "Response body exceeds " + maxBodyBytes + " bytes");
        }
        ContentType contentType = ContentType.parseLenient(entity.getContentType());
        Charset charset = contentType == null || contentType.getCharset() == null
                ? StandardCharsets.UTF_8 : contentType.getCharset();
        return new HttpResult.Success(status, new String(body, charset));
    }

    /**
     * {@code Retry-After}: число секунд или дата (RFC 9110 §10.2.3); нет или не разобран — ноль.
     */
    private Duration retryAfter(Header header) {
        if (header == null || header.getValue() == null || header.getValue().isBlank()) {
            return Duration.ZERO;
        }
        String value = header.getValue().strip();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException notSeconds) {
            try {
                ZonedDateTime until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration wait = Duration.between(clock.instant(), until.toInstant());
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (DateTimeParseException notDate) {
                return Duration.ZERO;
            }
        }
    }

    private static HttpResult fromException(IOException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof BlockedAddressException blocked) {
                return new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED, blocked.getMessage());
            }
        }
        if (exception instanceof ClientProtocolException) {
            return new HttpResult.PermanentFailure(HttpResult.Kind.CLIENT_ERROR, exception.toString());
        }
        return new HttpResult.TemporaryFailure(exception.toString(), Duration.ZERO);
    }

    private static CloseableHttpClient buildClient(ExternalHttpProperties properties, String userAgent) {
        AddressPolicy policy = new AddressPolicy(properties.allowPrivateAddresses());
        SystemDefaultDnsResolver checkingResolver = new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = super.resolve(host);
                for (InetAddress address : addresses) {
                    policy.requireAllowed(address);
                }
                return addresses;
            }
        };
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(properties.readTimeout().toMillis()))
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(properties.readTimeout().toMillis()))
                .setMaxRedirects(properties.maxRedirects())
                .setCircularRedirectsAllowed(false)
                .build();
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(checkingResolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .build())
                .setDefaultRequestConfig(requestConfig)
                .setUserAgent(userAgent)
                .disableAutomaticRetries()
                .build();
    }
}
