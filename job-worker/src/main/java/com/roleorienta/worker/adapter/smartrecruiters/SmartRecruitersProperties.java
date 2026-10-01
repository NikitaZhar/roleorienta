package com.roleorienta.worker.adapter.smartrecruiters;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера SmartRecruiters ({@code app.adapter.smartrecruiters.*}).
 *
 * @param careersUrl адрес кадровых страниц компаний; в тестах — заглушка
 * @param jobsUrl    адрес страниц вакансий; в тестах — заглушка
 * @param maxPages   потолок страниц списка за чтение; упор — неполное чтение
 */
@ConfigurationProperties("app.adapter.smartrecruiters")
public record SmartRecruitersProperties(
        @DefaultValue("https://careers.smartrecruiters.com") String careersUrl,
        @DefaultValue("https://jobs.smartrecruiters.com") String jobsUrl,
        @DefaultValue("500") int maxPages) {
}
