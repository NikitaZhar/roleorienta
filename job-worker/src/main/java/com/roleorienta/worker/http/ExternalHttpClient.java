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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

    private final CloseableHttpClient httpClient;
    private final HostBudget hostBudget;
    private final int maxBodyBytes;
    private final Clock clock;

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
