package com.roleorienta.worker.http;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки темпа запросов к источникам (§57, {@link RequestPacer}).
 *
 * @param defaultIntervalMs интервал между запросами к одному ключу (домену) по умолчанию, мс
 * @param intervalsMs       интервалы для конкретных доменов, мс (в YAML ключ с точками — в скобках:
 *                          {@code "[myworkdayjobs.com]": 2000})
 * @param maxWaitMs         сколько поток может ждать слота; дольше — задание откладывается
 * @param maxBackoffMs      потолок паузы по {@code Retry-After}, мс
 * @param defaultRetryAfterMs пауза при 429/503 без заголовка {@code Retry-After}, мс
 */
@ConfigurationProperties(prefix = "app.source.pacing")
public record SourcePacingProperties(
        @DefaultValue("1000") long defaultIntervalMs,
        @DefaultValue Map<String, Long> intervalsMs,
        @DefaultValue("20000") long maxWaitMs,
        @DefaultValue("3600000") long maxBackoffMs,
        @DefaultValue("60000") long defaultRetryAfterMs) {
}
