package com.roleorienta.worker.career;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Браузер без окна для проверки сайтов ({@code app.career.render.*}, §236).
 *
 * @param enabled {@code false} — браузер не запускается, проверка — только по HTML сервера (тесты)
 * @param timeout сколько ждать открытия страницы и, отдельно, окончания её запросов (меню строится скриптом)
 */
@ConfigurationProperties("app.career.render")
public record CareerRenderProperties(@DefaultValue("true") boolean enabled, @DefaultValue("20s") Duration timeout) {
}
