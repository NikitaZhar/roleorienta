package com.roleorienta.worker.adapter.greenhouse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера Greenhouse ({@code app.adapter.greenhouse.*}).
 *
 * @param baseUrl адрес Job Board API; в тестах — локальная заглушка
 */
@ConfigurationProperties("app.adapter.greenhouse")
public record GreenhouseProperties(@DefaultValue("https://boards-api.greenhouse.io") String baseUrl) {
}
