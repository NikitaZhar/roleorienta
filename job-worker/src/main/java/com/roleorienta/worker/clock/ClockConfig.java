package com.roleorienta.worker.clock;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Часы приложения — бином, чтобы приёмочные тесты подменяли время (сроки в днях проверяются без
 * ожидания, технический документ §14).
 */
@Configuration
public class ClockConfig {

    /**
     * @return системные часы в UTC
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
