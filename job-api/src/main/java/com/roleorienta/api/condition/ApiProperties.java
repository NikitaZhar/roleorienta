package com.roleorienta.api.condition;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки API ({@code app.*}).
 *
 * @param countries           поддерживаемые страны поиска (ISO 3166-1 alpha-2): те, для которых есть
 *                            сбор компаний (технический документ §16.16)
 * @param defaultPortionLimit лимит порции, если пользователь его не задал (технический документ §17)
 */
@ConfigurationProperties("app")
public record ApiProperties(List<String> countries, @DefaultValue("20") int defaultPortionLimit) {
}
