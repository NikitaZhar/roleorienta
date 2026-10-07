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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
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
 * Переадресации клиент проходит сам, шаг за шагом (аудит §66): каждый шаг — схема, robots.txt своего
 * происхождения и бюджет своего хоста; цикл или больше {@code max-redirects} шагов — постоянный отказ.
 * https://hc.apache.org/httpcomponents-client-5.4.x/</p>
 *
 * <p>Перед запросом место в очереди к хосту резервирует {@link HostBudget} (общий для реплик
 * промежуток между запросами); запрос ждёт своего места, а если очередь длиннее потолка —
 * возвращается временный отказ без запроса. {@code Retry-After} ответа сдвигает очередь хоста.
 * Каждый шаг переадресации резервирует бюджет своего хоста. User-Agent — из {@link PolitenessProperties}.</p>
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

    /**
     * Сообщение {@code UnknownHostException} при сбое самого DNS (EAI_AGAIN на Linux), а не при отсутствии домена:
     * такой отказ остаётся временным.
     */
    private static final String TEMPORARY_RESOLUTION_FAILURE = "Temporary failure in name resolution";
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final int STATUS_OK_MIN = 200;
    private static final int STATUS_OK_MAX = 299;
    private static final int STATUS_TEMPORARY_REDIRECT = 307;
    private static final int STATUS_PERMANENT_REDIRECT = 308;
    /** Переадресации, которые клиент проходит (как Apache HttpClient): 300, 304 и прочие 3xx — итог. */
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, STATUS_TEMPORARY_REDIRECT,
            STATUS_PERMANENT_REDIRECT);
    private static final int MAX_PORT = 65_535;
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
    private final int maxRedirects;
    private final Clock clock;
    private final String robotsToken;
    private final Map<String, CachedRobots> robotsByOrigin = new ConcurrentHashMap<>();
    private final Set<String> robotsExemptHosts;

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
        this.maxRedirects = properties.maxRedirects();
        this.clock = clock;
        this.robotsToken = politeness.userAgent().split("/", 2)[0].strip();
        this.robotsExemptHosts = politeness.robotsExemptHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
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
        return postJson(uri, json, Map.of());
    }

    /**
     * POST-запрос с JSON-телом и дополнительными заголовками (Taleo без заголовка часового пояса отвечает 500).
     *
     * @param uri     адрес
     * @param json    тело запроса
     * @param headers дополнительные заголовки
     * @return разобранный результат; исключений не бросает
     */
    public HttpResult postJson(URI uri, String json, Map<String, String> headers) {
        HttpPost request = new HttpPost(uri);
        request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        headers.forEach(request::setHeader);
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
        return follow(uri, request, true);
    }

    /**
     * Запрос с переадресациями: каждый шаг проверяется отдельно — схема, robots.txt происхождения шага (если
     * {@code checkRobots}; хосты-исключения — без проверки) и бюджет его хоста. 307 и 308 повторяют метод и тело,
     * прочие переадресации — {@code GET}.
     *
     * @param uri         адрес
     * @param request     запрос
     * @param checkRobots сверять ли шаги с robots.txt ({@code false} — запрос самого robots.txt)
     * @return итог последнего шага; успех после переадресации несёт конечный адрес
     */
    private HttpResult follow(URI uri, ClassicHttpRequest request, boolean checkRobots) {
        URI current = uri;
        ClassicHttpRequest step = request;
        Set<URI> visited = new HashSet<>();
        for (int hop = 0;; hop++) {
            String scheme = current.getScheme() == null ? "" : current.getScheme().toLowerCase(Locale.ROOT);
            if (!ALLOWED_SCHEMES.contains(scheme) || current.getHost() == null) {
                return new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED, "Not an http(s) URL: " + current);
            }
            if (checkRobots && !robotsExemptHosts.contains(current.getHost().toLowerCase(Locale.ROOT))) {
                Optional<HttpResult> refusal = robotsRefusal(current);
                if (refusal.isPresent()) {
                    return refusal.get();
                }
            }
            Hop answer = send(current, step);
            if (answer instanceof Done done) {
                return hop > 0 && done.result() instanceof HttpResult.Success success
                        ? new HttpResult.Success(success.status(), success.body(), current) : done.result();
            }
            Redirect redirect = (Redirect) answer;
            visited.add(current);
            if (hop >= maxRedirects || visited.contains(redirect.target())) {
                return new HttpResult.PermanentFailure(HttpResult.Kind.CLIENT_ERROR,
                        "Redirect loop or more than " + maxRedirects + " redirects: " + uri);
            }
            step = redirect.status() == STATUS_TEMPORARY_REDIRECT || redirect.status() == STATUS_PERMANENT_REDIRECT
                    ? ClassicRequestBuilder.copy(step).setUri(redirect.target()).build()
                    : new HttpGet(redirect.target());
            current = redirect.target();
        }
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
            switch (follow(robotsUri, new HttpGet(robotsUri), false)) {
                case HttpResult.Success success -> rules = RobotsRules.parse(success.body(), robotsToken);
                case HttpResult.PermanentFailure failure -> {
                    if (failure.kind() == HttpResult.Kind.NO_SUCH_HOST) {
                        return Optional.of(failure);
                    }
                    rules = RobotsRules.ALLOW_ALL;
                }
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

    /**
     * Один шаг: итог или переадресация.
     */
    private sealed interface Hop permits Done, Redirect {
    }

    /**
     * @param result итог шага
     */
    private record Done(HttpResult result) implements Hop {
    }

    /**
     * @param target адрес из {@code Location}, разрешённый от адреса шага
     * @param status код переадресации
     */
    private record Redirect(URI target, int status) implements Hop {
    }

    private Hop send(URI uri, ClassicHttpRequest request) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Optional<Duration> wait = hostBudget.reserve(host);
        if (wait.isEmpty()) {
            return new Done(new HttpResult.TemporaryFailure("Host budget exhausted: " + host, Duration.ZERO));
        }
        try {
            Thread.sleep(wait.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Done(new HttpResult.TemporaryFailure("Interrupted while waiting for host budget",
                    Duration.ZERO));
        }
        Hop hop;
        try {
            hop = httpClient.execute(request, response -> answer(uri, response));
        } catch (IOException exception) {
            hop = new Done(fromException(exception));
        }
        if (hop instanceof Done done && done.result() instanceof HttpResult.TemporaryFailure temporary
                && temporary.retryAfter().isPositive()) {
            hostBudget.backOff(host, temporary.retryAfter());
        }
        return hop;
    }

    /**
     * 301, 302, 303, 307, 308 с {@code Location} — переадресация (адрес разрешается от адреса шага; у адреса без пути —
     * от {@code /}: {@code URI.resolve} иначе склеивает хост и относительный путь); порт цели 0 или больше 65535 —
     * постоянный отказ (запрос с таким адресом Apache HttpClient не строит — исключение); прочее — итог.
     */
    private Hop answer(URI uri, ClassicHttpResponse response) throws IOException {
        int status = response.getCode();
        Header location = response.getFirstHeader(HttpHeaders.LOCATION);
        if (REDIRECT_STATUSES.contains(status) && location != null
                && location.getValue() != null && !location.getValue().isBlank()) {
            URI base = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? uri.resolve("/") : uri;
            try {
                URI target = base.resolve(location.getValue().strip());
                if (target.getPort() == 0 || target.getPort() > MAX_PORT) {
                    return new Done(new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED,
                            "Redirect port out of range: " + target));
                }
                return new Redirect(target, status);
            } catch (IllegalArgumentException malformed) {
                return new Done(new HttpResult.PermanentFailure(HttpResult.Kind.CLIENT_ERROR,
                        "Malformed redirect location: " + location.getValue()));
            }
        }
        return new Done(toResult(response));
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
            if (cause instanceof UnknownHostException unknown && !String.valueOf(unknown.getMessage())
                    .contains(TEMPORARY_RESOLUTION_FAILURE)) {
                return new HttpResult.PermanentFailure(HttpResult.Kind.NO_SUCH_HOST, unknown.toString());
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
                .build();
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(checkingResolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .build())
                .setDefaultRequestConfig(requestConfig)
                .setUserAgent(userAgent)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }
}
