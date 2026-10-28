package com.roleorienta.worker.stateportal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки добора объявлений profesia.sk, которых нет на портале ({@code app.state-portal.profesia-offers.*}; §219).
 *
 * @param enabled      {@code false} — не добираются: задания цепочки завершаются сразу, без запросов
 * @param pagesPerTask страниц объявлений за задание — квота в час: продолжение — в следующем часу (§101)
 * @param timeBudget   после этого срока задание не начинает новую страницу — укладывается в аренду
 */
@ConfigurationProperties("app.state-portal.profesia-offers")
public record ProfesiaOffersProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int pagesPerTask,
        @DefaultValue("3m") Duration timeBudget) {
}
