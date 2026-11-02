package com.roleorienta.worker.browser;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Браузер без окна ({@code app.browser.*}; §236, §241): проверка сайтов и чтение кадровых страниц.
 *
 * @param enabled      {@code false} — браузер не запускается, страницы — только по HTML сервера (тесты)
 * @param timeout      сколько ждать открытия страницы и, отдельно, окончания её запросов (меню строится скриптом)
 * @param expandClicks сколько раз нажимать «показать ещё» на странице списка
 * @param expandBudget сколько всего длятся нажатия «показать ещё» на одной странице
 */
@ConfigurationProperties("app.browser")
public record BrowserProperties(@DefaultValue("true") boolean enabled, @DefaultValue("20s") Duration timeout,
        @DefaultValue("50") int expandClicks, @DefaultValue("120s") Duration expandBudget) {
}
