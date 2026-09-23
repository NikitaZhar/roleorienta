package com.roleorienta.worker.collect;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Часы приложения (UTC) — граница суток дневного бюджета деталей (§63). Отдельным бином,
 * чтобы тесты подставляли фиксированное время.
 */
@Configuration
public class CollectClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
