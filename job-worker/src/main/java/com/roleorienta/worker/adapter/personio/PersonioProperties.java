package com.roleorienta.worker.adapter.personio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера Personio ({@code app.adapter.personio.*}).
 *
 * @param baseUrlTemplate адрес витрины компании, {@code {board}} — поддомен; в тестах — заглушка
 */
@ConfigurationProperties("app.adapter.personio")
public record PersonioProperties(@DefaultValue("https://{board}.jobs.personio.de") String baseUrlTemplate) {
}
