package com.roleorienta.worker.crawl;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки обходов ({@code app.crawl.*}).
 *
 * @param unavailableAfter срок серии временных отказов источника до статуса «недоступен»
 *                         (технический документ §17)
 */
@ConfigurationProperties("app.crawl")
public record CrawlProperties(@DefaultValue("7d") Duration unavailableAfter) {
}
