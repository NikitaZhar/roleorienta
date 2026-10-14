package com.roleorienta.worker.collect;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки сбора ({@code app.collect.*}).
 *
 * @param maxContentRequestsPerRead потолок запросов текста публикаций за одно чтение источника;
 *                                  остальные тексты дочитываются следующими чтениями
 * @param maxChecksPerRead          потолок проверок пропавших публикаций за одно чтение источника
 *                                  с фильтром по стране; непроверенные не засчитываются отсутствующими
 * @param readAfter                 через сколько после начала последнего чтения источник читается снова (чтение не
 *                                  реже раза в сутки при тике раз в час, §103)
 * @param sharedHostProviders       провайдеры, у которых один хост на все источники: их источники читаются по одному
 *                                  — медленный общий хост держит не больше одного обработчика (§103)
 */
@ConfigurationProperties("app.collect")
public record CollectProperties(@DefaultValue("200") int maxContentRequestsPerRead,
        @DefaultValue("200") int maxChecksPerRead,
        @DefaultValue("23h") Duration readAfter,
        @DefaultValue({"greenhouse", "smartrecruiters", "nalgoo"})
        List<String> sharedHostProviders) {
}
