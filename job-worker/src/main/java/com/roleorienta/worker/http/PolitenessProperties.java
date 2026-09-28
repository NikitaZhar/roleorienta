package com.roleorienta.worker.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Вежливость к внешним сайтам ({@code app.http.politeness.*}, технический документ §10, §16.11).
 *
 * @param userAgent    единый User-Agent с контактом
 * @param hostInterval промежуток между запросами к одному хосту по всем репликам
 * @param hostMaxWait  дольше этого запрос не ждёт своей очереди к хосту — временный отказ
 */
@ConfigurationProperties("app.http.politeness")
public record PolitenessProperties(
        @DefaultValue("Roleorienta/0.1 (+https://github.com/NikitaZhar/roleorienta)") String userAgent,
        @DefaultValue("1s") Duration hostInterval,
        @DefaultValue("30s") Duration hostMaxWait) {
}
