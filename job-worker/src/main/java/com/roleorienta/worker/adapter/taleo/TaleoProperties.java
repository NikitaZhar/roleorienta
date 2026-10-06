package com.roleorienta.worker.adapter.taleo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера Oracle Taleo ({@code app.adapter.taleo.*}).
 *
 * @param siteUrl  адрес сайта Taleo; {@code {host}} — хост компании ({@code molgroup.taleo.net}); в тестах — заглушка
 * @param maxPages потолок страниц списка за чтение (по 25 вакансий); упор — неполное чтение
 */
@ConfigurationProperties("app.adapter.taleo")
public record TaleoProperties(
        @DefaultValue("https://{host}") String siteUrl,
        @DefaultValue("40") int maxPages) {
}
