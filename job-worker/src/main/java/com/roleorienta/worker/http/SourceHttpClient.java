package com.roleorienta.worker.http;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
 * <p>Ограничение: жёсткий потолок размера тела ответа (стриминговый) в этот срез
 * не входит — время ограничено тайм-аутами; см. project-notes §32 «Что НЕ вошло».</p>
 */
@Component
public class SourceHttpClient {

    private final SsrfGuard ssrfGuard;
    private final RestClient restClient;

    /**
     * @param ssrfGuard        контур защиты от SSRF (§9, A13)
     * @param connectTimeoutMs тайм-аут установления соединения, мс
     * @param readTimeoutMs    тайм-аут чтения ответа, мс
     * @param maxRedirects     максимум переходов по редиректам (каждый ре-валидируется)
     */
    public SourceHttpClient(
            SsrfGuard ssrfGuard,
            @Value("${app.collect.http.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${app.collect.http.read-timeout-ms:15000}") long readTimeoutMs,
            @Value("${app.collect.http.max-redirects:5}") int maxRedirects) {
        this.ssrfGuard = ssrfGuard;
        this.restClient = buildRestClient(ssrfGuard, connectTimeoutMs, readTimeoutMs, maxRedirects);
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
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
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
        ssrfGuard.checkScheme(url);
        try {
            return restClient.get().uri(url).retrieve().body(String.class);
        } catch (RuntimeException e) {
            throw unwrapSsrf(e);
        }
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
