package com.roleorienta.worker.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки сбора ({@code app.collect.*}).
 *
 * @param maxContentRequestsPerRead потолок запросов текста публикаций за одно чтение источника;
 *                                  остальные тексты дочитываются следующими чтениями
 */
@ConfigurationProperties("app.collect")
public record CollectProperties(@DefaultValue("200") int maxContentRequestsPerRead) {
}
