package com.roleorienta.worker.site;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки поиска сайтов компаний в Common Crawl ({@code app.site.*}).
 *
 * @param indexUrl        сервер индекса (список обходов {@code collinfo.json})
 * @param dataUrl         хранилище данных обходов (индекс и архивы страниц)
 * @param requestInterval пауза перед каждым запросом к Common Crawl — бережная нагрузка
 * @param blocksPerTask   блоков индекса за одно задание (блок — до ~3000 адресов, до сотни страниц)
 * @param timeout         таймаут соединения и ожидания ответа
 */
@ConfigurationProperties("app.site")
public record CommonCrawlProperties(
        @DefaultValue("https://index.commoncrawl.org") String indexUrl,
        @DefaultValue("https://data.commoncrawl.org") String dataUrl,
        @DefaultValue("200ms") Duration requestInterval,
        @DefaultValue("20") int blocksPerTask,
        @DefaultValue("60s") Duration timeout) {
}
