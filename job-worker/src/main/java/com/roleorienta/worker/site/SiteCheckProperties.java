package com.roleorienta.worker.site;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки проверки сайта ({@code app.site-check.*}; {@link SiteVerifier}, технический документ §5.1, §17).
 *
 * @param homeUrl            адрес главной сайта по хосту; {@code {host}} — хост; в тестах — заглушка
 * @param domainMarketplaces площадки продажи доменов ({@link DomainMarketplaces}, §135); пусто — нет
 */
@ConfigurationProperties("app.site-check")
public record SiteCheckProperties(@DefaultValue("https://{host}/") String homeUrl,
        @DefaultValue List<String> domainMarketplaces) {
}
