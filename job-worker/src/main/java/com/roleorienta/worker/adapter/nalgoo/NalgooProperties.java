package com.roleorienta.worker.adapter.nalgoo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера Nalgoo ({@code app.adapter.nalgoo.*}).
 *
 * @param apiUrl  адрес публичного API Nalgoo; в тестах — заглушка
 * @param siteUrl адрес кадрового сайта Nalgoo, {@code {board}} — доска (поддомен); с него берётся имя организации в API,
 *                когда оно не равно поддомену (§253); в тестах — заглушка
 */
@ConfigurationProperties("app.adapter.nalgoo")
public record NalgooProperties(@DefaultValue("https://ats.nalgoo.com/api/v3") String apiUrl,
        @DefaultValue("https://{board}.nalgoo-jobs.com/") String siteUrl) {
}
