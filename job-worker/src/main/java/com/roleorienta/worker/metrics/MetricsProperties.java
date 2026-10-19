package com.roleorienta.worker.metrics;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки показателей ядра ({@code app.metrics.*}; §131). Включение — {@code enabled} (читает
 * {@link ComputeMetricsTick}), период тика — {@code interval-ms}.
 *
 * @param zone часовой пояс суток показателей (технический документ §11: «часовой пояс суток — настройка»)
 */
@ConfigurationProperties("app.metrics")
public record MetricsProperties(@DefaultValue("Europe/Bratislava") ZoneId zone) {
}
