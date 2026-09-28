package com.roleorienta.worker.adapter.workday;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера Workday ({@code app.adapter.workday.*}).
 *
 * @param baseUrlTemplate адрес витрины, {@code {host}} — хост тенанта; в тестах — заглушка
 * @param pageSize        публикаций на страницу списка (Workday отдаёт не больше 20)
 * @param maxPages        потолок страниц за одно чтение; упор в потолок — неполное чтение
 */
@ConfigurationProperties("app.adapter.workday")
public record WorkdayProperties(
        @DefaultValue("https://{host}") String baseUrlTemplate,
        @DefaultValue("20") int pageSize,
        @DefaultValue("100") int maxPages) {
}
