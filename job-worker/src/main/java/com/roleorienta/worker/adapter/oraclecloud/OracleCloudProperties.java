package com.roleorienta.worker.adapter.oraclecloud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера Oracle Recruiting Cloud ({@code app.adapter.oraclecloud.*}).
 *
 * @param siteUrl  адрес сайта Oracle; {@code {host}} — хост работодателя ({@code ekiz.fa.em2.oraclecloud.com}); в
 *                 тестах — заглушка
 * @param maxPages потолок страниц списка за чтение (по 25 вакансий страны сбора); упор — неполное чтение
 */
@ConfigurationProperties("app.adapter.oraclecloud")
public record OracleCloudProperties(
        @DefaultValue("https://{host}") String siteUrl,
        @DefaultValue("40") int maxPages) {
}
