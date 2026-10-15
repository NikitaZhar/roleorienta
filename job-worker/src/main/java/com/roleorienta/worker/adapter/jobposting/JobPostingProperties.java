package com.roleorienta.worker.adapter.jobposting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера разметки schema.org {@code JobPosting} ({@code app.adapter.jobposting.*}).
 *
 * @param maxPages потолок страниц вакансий за одно чтение (технический документ §17; 100 — решение владельца
 *                 2026-10-15, аудит §121); упор — неполное чтение, следующее чтение берёт следующие ссылки
 */
@ConfigurationProperties("app.adapter.jobposting")
public record JobPostingProperties(@DefaultValue("100") int maxPages) {
}
