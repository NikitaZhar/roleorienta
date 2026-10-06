package com.roleorienta.worker.adapter.successfactors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера SAP SuccessFactors Career Site Builder ({@code app.adapter.successfactors.*}).
 *
 * @param siteUrl  адрес кадрового сайта по доске; {@code {host}} — хост сайта (доска); в тестах — заглушка
 * @param maxPages потолок страниц списка за чтение (по 20–25 вакансий); упор — неполное чтение
 */
@ConfigurationProperties("app.adapter.successfactors")
public record SuccessFactorsProperties(
        @DefaultValue("https://{host}") String siteUrl,
        @DefaultValue("40") int maxPages) {
}
