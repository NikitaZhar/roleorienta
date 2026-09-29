package com.roleorienta.worker.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки сбора ({@code app.collect.*}).
 *
 * @param maxContentRequestsPerRead потолок запросов текста публикаций за одно чтение источника;
 *                                  остальные тексты дочитываются следующими чтениями
 * @param maxChecksPerRead          потолок проверок пропавших публикаций за одно чтение источника
 *                                  с фильтром по стране; непроверенные не засчитываются отсутствующими
 */
@ConfigurationProperties("app.collect")
public record CollectProperties(@DefaultValue("200") int maxContentRequestsPerRead,
        @DefaultValue("200") int maxChecksPerRead) {
}
