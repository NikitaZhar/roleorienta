package com.roleorienta.worker.adapter.jobposting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера разметки schema.org {@code JobPosting} ({@code app.adapter.jobposting.*}).
 *
 * @param maxPages потолок страниц вакансий за одно чтение (технический документ §17); упор —
 *                 неполное чтение
 */
@ConfigurationProperties("app.adapter.jobposting")
public record JobPostingProperties(@DefaultValue("200") int maxPages) {
}
