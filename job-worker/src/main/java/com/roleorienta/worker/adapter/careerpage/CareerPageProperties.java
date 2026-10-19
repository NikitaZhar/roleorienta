package com.roleorienta.worker.adapter.careerpage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки адаптера собственной кадровой страницы ({@code app.adapter.careerpage.*}; §136).
 *
 * @param maxPages   страниц списка за одно чтение не больше; упор — неполное чтение
 * @param maxContent символов текста страницы вакансии не больше (текст служит отбору)
 */
@ConfigurationProperties("app.adapter.careerpage")
public record CareerPageProperties(@DefaultValue("10") int maxPages, @DefaultValue("20000") int maxContent) {
}
