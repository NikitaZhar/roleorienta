package com.roleorienta.worker.site;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки проверки сайта ({@code app.site-check.*}; {@link SiteVerifier}, технический документ §5.1, §17).
 *
 * @param homeUrl адрес главной сайта по хосту; {@code {host}} — хост; в тестах — заглушка
 */
@ConfigurationProperties("app.site-check")
public record SiteCheckProperties(@DefaultValue("https://{host}/") String homeUrl) {
}
