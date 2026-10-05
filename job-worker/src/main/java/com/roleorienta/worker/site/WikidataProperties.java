package com.roleorienta.worker.site;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки шага «Wikidata» поиска сайта компании ({@code app.wikidata.*}; технический документ §5.1, §17).
 *
 * @param endpoint     адрес SPARQL-сервиса Wikidata; в тестах — заглушка
 * @param pageSize     строк ответа на запрос (запрос постраничный: {@code LIMIT}/{@code OFFSET})
 * @param maxPages     потолок страниц за задание; упор — задание повторится позже
 * @param sitesPerTask компаний, чей сайт проверяется одним заданием (≈ 1 запрос на сайт — в аренде)
 */
@ConfigurationProperties("app.wikidata")
public record WikidataProperties(
        @DefaultValue("https://query.wikidata.org/sparql") URI endpoint,
        @DefaultValue("5000") int pageSize,
        @DefaultValue("20") int maxPages,
        @DefaultValue("100") int sitesPerTask) {
}
