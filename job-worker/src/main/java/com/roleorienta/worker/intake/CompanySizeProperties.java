package com.roleorienta.worker.intake;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки шага «число сотрудников из RÚZ» ({@code app.company-size.*}; технический документ §5.1, §17).
 *
 * @param baseUrl          адрес RÚZ Open API; в тестах — заглушка
 * @param companiesPerTask компаний за задание (два запроса на компанию, пауза 1 с на хост — задание укладывается
 *                         в аренду)
 * @param recheckAfter     срок до повторного запроса (категория меняется с годовой отчётностью)
 */
@ConfigurationProperties("app.company-size")
public record CompanySizeProperties(
        @DefaultValue("https://www.registeruz.sk/cruz-public/api") URI baseUrl,
        @DefaultValue("200") int companiesPerTask,
        @DefaultValue("365d") Duration recheckAfter) {
}
