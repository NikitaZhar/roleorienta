package com.roleorienta.worker.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки HTTP-клиента сбора ({@link SourceHttpClient}, §5/§9). Сгруппированы в запись,
 * чтобы конструктор клиента не превышал лимит «≤5 параметров» (контракт §3.10). Флаг
 * {@code app.collect.http.allow-private-addresses} относится к тому же префиксу, но читается
 * {@link SsrfGuard}.
 *
 * @param connectTimeoutMs тайм-аут установления соединения, мс
 * @param readTimeoutMs    тайм-аут чтения ответа, мс
 * @param maxRedirects     максимум переходов по редиректам (каждый ре-валидируется, A13)
 * @param maxBodyBytes     потолок размера тела ответа, байт (B2): больше — запрос отклоняется,
 *                         не дочитывая тело; 5 МБ с запасом покрывают ленты Workday (десятки КБ)
 *                         и страницу индекса Common Crawl (~0,5 МБ при {@code page-size: 1})
 */
@ConfigurationProperties(prefix = "app.collect.http")
public record SourceHttpProperties(
        @DefaultValue("5000") long connectTimeoutMs,
        @DefaultValue("15000") long readTimeoutMs,
        @DefaultValue("5") int maxRedirects,
        @DefaultValue("5242880") int maxBodyBytes) {
}
