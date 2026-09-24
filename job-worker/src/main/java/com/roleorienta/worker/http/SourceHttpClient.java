package com.roleorienta.worker.http;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP-клиент для чтения лент источников — единственная точка исходящих запросов
 * сбора/обнаружения (§5). Все адаптеры ходят наружу только через него.
 *
 * <p>Реализован на Apache HttpClient 5 (за {@link RestClient}), потому что он
 * позволяет подставить собственный резолвер имён — обязательное условие защиты от
 * SSRF/DNS-rebinding (§9, A13): {@link SsrfGuard#dnsResolver()} проверяет каждый
 * резолвнутый адрес по {@link AddressPolicy}, и подключение идёт только на
 * разрешённый IP без повторного резолва. Стандартный {@code java.net.http.HttpClient}
 * такой подстановки не даёт. Тайм-ауты соединения/чтения ограничивают время, чтобы
 * зависший источник не занимал обработчик (§5). Редиректы включены, но каждый
 * переход снова проходит резолвер (ре-валидация адреса, A13) и ограничен числом
 * переходов.</p>
 *
 * <p>Клиент один на приложение и переиспользуется. Неуспешный статус (4xx/5xx)
 * {@link RestClient} превращает в исключение — оно поднимается обработчику как
 * неуспех задания (повтор, затем DLQ).</p>
 *
 * <p><b>Потолок размера тела (B2, §71).</b> Тело читается потоком не больше
 * {@link SourceHttpProperties#maxBodyBytes()} байт; если источник отдаёт больше — запрос
 * отклоняется {@link ResponseTooLargeException}, не дочитывая и не держа в памяти остаток
 * (защита от «бесконечного» или огромного ответа, §9). Поэтому ответ читается через
 * {@code exchange}, а не {@code retrieve().body(String.class)}: неуспешный статус
 * обрабатывается тем же {@link DefaultResponseErrorHandler}, что и у {@code retrieve()} —
 * исключения (напр. {@code HttpClientErrorException.TooManyRequests}) прежние. Кодировка —
 * из {@code Content-Type}, без неё — UTF-8.</p>
 */
@Component
public class SourceHttpClient {

    /** Потолок тела по умолчанию (как в {@link SourceHttpProperties}), байт — 5 МБ. */
    static final int DEFAULT_MAX_BODY_BYTES = 5 * 1024 * 1024;

    /** Обработчик неуспешных статусов — тот же, что у {@code retrieve()}. */
    private static final ResponseErrorHandler STATUS_ERRORS = new DefaultResponseErrorHandler();

    private final SsrfGuard ssrfGuard;
    private final RestClient restClient;
    private final int maxBodyBytes;
    private final RequestPacer pacer;
    private final long defaultRetryAfterMs;

    /**
     * @param ssrfGuard контур защиты от SSRF (§9, A13)
     * @param http      тайм-ауты, редиректы и потолок размера тела ответа
     * @param pacer     темп запросов к источникам (вежливость, §57)
     * @param pacing    настройки темпа (пауза по умолчанию при 429 без {@code Retry-After})
     */
    @Autowired
    public SourceHttpClient(
            SsrfGuard ssrfGuard,
            SourceHttpProperties http,
            RequestPacer pacer,
            SourcePacingProperties pacing) {
        this.ssrfGuard = ssrfGuard;
        this.restClient = buildRestClient(ssrfGuard, http.connectTimeoutMs(), http.readTimeoutMs(),
                http.maxRedirects());
        this.maxBodyBytes = http.maxBodyBytes();
        this.pacer = pacer;
        this.defaultRetryAfterMs = pacing.defaultRetryAfterMs();
    }

    /**
     * Клиент без темпа запросов и с потолком тела по умолчанию — для тестов против
     * локальных заглушек.
     *
     * @param ssrfGuard        контур защиты от SSRF
     * @param connectTimeoutMs тайм-аут соединения, мс
     * @param readTimeoutMs    тайм-аут чтения, мс
     * @param maxRedirects     максимум переходов по редиректам
     */
    public SourceHttpClient(SsrfGuard ssrfGuard, long connectTimeoutMs, long readTimeoutMs, int maxRedirects) {
        this(ssrfGuard, new SourceHttpProperties(connectTimeoutMs, readTimeoutMs, maxRedirects, DEFAULT_MAX_BODY_BYTES),
                RequestPacer.unpaced(), new SourcePacingProperties(0, Map.of(), Long.MAX_VALUE, 0, 0));
    }

    private static RestClient buildRestClient(
            SsrfGuard guard, long connectMs, long readMs, int maxRedirects) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
                .setSocketTimeout(Timeout.ofMilliseconds(readMs))
                .build();
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(guard.dnsResolver())
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(readMs))
                .setMaxRedirects(maxRedirects)
                .setCircularRedirectsAllowed(false)
                .build();
        // disableAutomaticRetries: у Apache HttpClient 5 по умолчанию включён
        // DefaultHttpRequestRetryStrategy — он сам повторяет 429/503 и молча СПИТ столько,
        // сколько просит Retry-After (хоть минуты), держа поток. Повторы и паузы — наша
        // забота (RequestPacer, брокер, тик), поэтому встроенные выключены (§57).
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .build();
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Выполняет GET и возвращает тело ответа как строку.
     *
     * @param url полный адрес запроса
     * @return тело ответа
     * @throws SsrfBlockedException если схема запрещена или адрес назначения
     *                             (в т.ч. после редиректа) не проходит проверку A13
     */
    public String getBody(String url) {
        return execute(url, () -> restClient.get().uri(url).exchange(this::readBody));
    }

    /**
     * Выполняет GET с {@code Accept: application/json} и возвращает тело ответа.
     * Нужен для лент/деталей, отдающих JSON только при явном {@code Accept} (напр.
     * Workday cxs detail-endpoint). Проходит тот же SSRF-контур, что и {@link #getBody}.
     *
     * @param url полный адрес запроса
     * @return тело ответа
     * @throws SsrfBlockedException если схема запрещена или адрес не проходит проверку A13
     */
    public String getJson(String url) {
        return execute(url, () ->
                restClient.get().uri(url).accept(MediaType.APPLICATION_JSON).exchange(this::readBody));
    }

    /**
     * Выполняет POST с JSON-телом ({@code Content-Type}/{@code Accept: application/json})
     * и возвращает тело ответа. Нужен для лент, у которых список отдаётся POST-запросом
     * (напр. Workday: {@code /wday/cxs/{tenant}/{site}/jobs}). Обязателен как
     * <b>единственная</b> точка исходящих запросов сбора — идёт через тот же
     * SSRF-защищённый клиент, что и GET (§9, A13): {@code Jsoup.connect}, сторонний SDK
     * или отдельный raw-клиент этот путь обходить не должны.
     *
     * @param url      полный адрес запроса
     * @param jsonBody тело запроса (сериализованный JSON)
     * @return тело ответа
     * @throws SsrfBlockedException если схема запрещена или адрес не проходит проверку A13
     */
    public String postJson(String url, String jsonBody) {
        return postJson(url, jsonBody, Map.of());
    }

    /**
     * То же, что {@link #postJson(String, String)}, с дополнительными заголовками запроса
     * (напр. {@code Accept-Language: en-US} — Workday локализует названия фасетов по нему,
     * §56). {@code Content-Type}/{@code Accept} задаются методом и заголовками не
     * переопределяются.
     *
     * @param url      полный адрес запроса
     * @param jsonBody тело запроса (сериализованный JSON)
     * @param headers  дополнительные заголовки
     * @return тело ответа
     * @throws SsrfBlockedException если схема запрещена или адрес не проходит проверку A13
     */
    public String postJson(String url, String jsonBody, Map<String, String> headers) {
        return execute(url, () -> restClient.post().uri(url)
                .headers(h -> headers.forEach(h::set))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .exchange(this::readBody));
    }

    /**
     * Общий путь всех запросов: проверка схемы (A13) → слот темпа для домена (§57) →
     * запрос. Ответ {@code 429} или {@code 503} с {@code Retry-After} отодвигает следующий
     * слот домена ({@code 429} без заголовка — на паузу по умолчанию); исключение
     * пробрасывается как есть — повтор решает вызывающий/брокер.
     */
    private String execute(String url, Supplier<String> call) {
        ssrfGuard.checkScheme(url);
        String key = RequestPacer.keyOf(url);
        pacer.acquire(key);
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            retryAfter(e).ifPresent(delay -> pacer.backoff(key, delay));
            throw e;
        } catch (RuntimeException e) {
            throw unwrapSsrf(e);
        }
    }

    /**
     * Ответ источника: неуспешный статус → то же исключение, что у {@code retrieve()};
     * иначе тело не больше {@link #maxBodyBytes} байт как строка.
     *
     * @throws ResponseTooLargeException если тело больше потолка (остаток не читается)
     * @throws IOException               ошибка чтения (RestClient оборачивает её в
     *                                   {@code ResourceAccessException})
     */
    private String readBody(HttpRequest request, ClientHttpResponse response) throws IOException {
        if (STATUS_ERRORS.hasError(response)) {
            STATUS_ERRORS.handleError(request.getURI(), request.getMethod(), response);
        }
        byte[] body;
        try (InputStream stream = response.getBody()) {
            body = stream.readNBytes(maxBodyBytes + 1);
        }
        if (body.length > maxBodyBytes) {
            throw new ResponseTooLargeException(request.getURI() + ": тело ответа больше "
                    + maxBodyBytes + " байт");
        }
        MediaType type = response.getHeaders().getContentType();
        Charset charset = type != null && type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
        return new String(body, charset);
    }

    /** Тело ответа источника больше потолка {@link SourceHttpProperties#maxBodyBytes()} (B2). */
    public static class ResponseTooLargeException extends IllegalStateException {
        ResponseTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Пауза, которую просит источник: {@code Retry-After} в секундах или HTTP-дате — для
     * {@code 429} и {@code 503}; {@code 429} без заголовка — пауза по умолчанию.
     */
    Optional<Duration> retryAfter(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status != 429 && status != 503) {
            return Optional.empty();
        }
        String header = e.getResponseHeaders() == null
                ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header != null && !header.isBlank()) {
            String value = header.strip();
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
            } catch (NumberFormatException notSeconds) {
                try {
                    ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                    return Optional.of(Duration.between(ZonedDateTime.now(at.getZone()), at));
                } catch (DateTimeParseException notDate) {
                    // неразборный заголовок — как отсутствующий
                }
            }
        }
        return status == 429 ? Optional.of(Duration.ofMillis(defaultRetryAfterMs)) : Optional.empty();
    }

    /**
     * Если где-то в цепочке причин есть {@link SsrfBlockedException} (брошено из
     * резолвера внутри HTTP-клиента), поднимает именно его — чтобы тип отказа был
     * однозначным для вызывающего и тестов. Иначе пробрасывает исходное исключение.
     */
    private RuntimeException unwrapSsrf(RuntimeException original) {
        Throwable cursor = original;
        while (cursor != null) {
            if (cursor instanceof SsrfBlockedException blocked) {
                return blocked;
            }
            cursor = cursor.getCause();
        }
        return original;
    }
}
