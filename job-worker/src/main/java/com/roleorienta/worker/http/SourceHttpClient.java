package com.roleorienta.worker.http;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP-клиент для чтения лент источников.
 *
 * <p>Обёртка над {@link RestClient} — синхронным HTTP-клиентом Spring Framework
 * (https://docs.spring.io/spring-framework/reference/integration/rest-clients.html).
 * Под капотом — {@link JdkClientHttpRequestFactory} поверх стандартного
 * {@link java.net.http.HttpClient} (JDK): тайм-аут соединения задаётся на
 * {@code HttpClient}, тайм-аут чтения — на фабрике. Клиент один на приложение
 * (создаётся при старте) и переиспользуется. Тайм-ауты ограничивают время, чтобы
 * зависший источник не занимал обработчик бесконечно (§5 техдока: «сбой одного
 * источника не должен занимать все обработчики»).</p>
 *
 * <p>Это единая точка исходящих HTTP-запросов к источникам — сюда в отдельной
 * задаче добавляется защита от SSRF (проверка адреса перед запросом, §9, A13/A14),
 * не затрагивая адаптеры.</p>
 */
@Component
public class SourceHttpClient {

    private final RestClient restClient;

    /**
     * @param connectTimeoutMs тайм-аут установления соединения, мс
     * @param readTimeoutMs    тайм-аут чтения ответа, мс
     */
    public SourceHttpClient(
            @Value("${app.collect.http.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${app.collect.http.read-timeout-ms:15000}") long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Выполняет GET и возвращает тело ответа как строку.
     *
     * <p>Неуспешный статус (4xx/5xx) {@link RestClient} по умолчанию превращает в
     * исключение — оно поднимается вызывающему обработчику, который трактует это как
     * неуспех задания (повтор, затем DLQ).</p>
     *
     * @param url полный адрес запроса
     * @return тело ответа
     */
    public String getBody(String url) {
        return restClient.get().uri(url).retrieve().body(String.class);
    }
}
