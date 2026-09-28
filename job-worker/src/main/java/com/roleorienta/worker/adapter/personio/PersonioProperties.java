package com.roleorienta.worker.adapter.personio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера Personio ({@code app.adapter.personio.*}).
 *
 * @param baseUrlTemplate адрес витрины, {@code {board}} — хост витрины; в тестах — заглушка
 */
@ConfigurationProperties("app.adapter.personio")
public record PersonioProperties(@DefaultValue("https://{board}") String baseUrlTemplate) {
}
