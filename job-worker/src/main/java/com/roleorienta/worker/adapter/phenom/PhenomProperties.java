package com.roleorienta.worker.adapter.phenom;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера Phenom ({@code app.adapter.phenom.*}).
 *
 * @param siteUrl  адрес кадрового сайта по доске; {@code {board}} — хост и путь языка (доска); в тестах — заглушка
 * @param maxPages потолок страниц списка за чтение (по 10 вакансий); упор — неполное чтение
 */
@ConfigurationProperties("app.adapter.phenom")
public record PhenomProperties(
        @DefaultValue("https://{board}") String siteUrl,
        @DefaultValue("40") int maxPages) {
}
