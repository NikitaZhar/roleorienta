package com.roleorienta.worker.site;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Официальные сайты из Wikidata по словацкому IČO: все элементы со свойствами P8174 («словацкий
 * регистрационный номер») и P856 («официальный сайт») — постраничным SPARQL-запросом к публичному сервису
 * (данные CC0; https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service). Таких элементов тысячи, поэтому
 * один постраничный запрос дешевле, чем запрос по каждой компании. Запросы — через {@link ExternalHttpClient}
 * (бюджет на хост, robots.txt, User-Agent с контактом).
 */
@Component
public class WikidataClient {

    /** Порядок по IČO — страницы не пересекаются. */
    static final String QUERY = "SELECT ?item ?ico ?site WHERE { ?item wdt:P8174 ?ico ; wdt:P856 ?site . } "
            + "ORDER BY ?ico ?site LIMIT %d OFFSET %d";
    private static final int DIGITS = 8;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(WikidataClient.class);

    private final ExternalHttpClient httpClient;
    private final WikidataProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес сервиса и размер страницы
     */
    public WikidataClient(ExternalHttpClient httpClient, WikidataProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * @return все пары «IČO → официальный сайт»; пусто — сервис не ответил, ответ не разобран или страниц
     *         больше {@link WikidataProperties#maxPages()}
     */
    public Optional<List<WikidataSite>> sites() {
        List<WikidataSite> sites = new ArrayList<>();
        for (int page = 0; page < properties.maxPages(); page++) {
            String query = String.format(QUERY, properties.pageSize(), page * properties.pageSize());
            URI uri = URI.create(properties.endpoint() + "?format=json&query="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpResult result = httpClient.get(uri);
            if (!(result instanceof HttpResult.Success success)) {
                LOG.warn("Wikidata page {} not read: {}", page, result);
                return Optional.empty();
            }
            Optional<JsonNode> bindings = bindings(success.body());
            if (bindings.isEmpty()) {
                return Optional.empty();
            }
            sites.addAll(toSites(bindings.get()));
            if (bindings.get().size() < properties.pageSize()) {
                return Optional.of(sites);
            }
        }
        LOG.warn("Wikidata answer exceeds {} pages", properties.maxPages());
        return Optional.empty();
    }

    /**
     * @return строки ответа SPARQL JSON ({@code results.bindings}); пусто — ответ не разобран
     */
    private static Optional<JsonNode> bindings(String body) {
        try {
            return Optional.of(JSON.readTree(body).path("results").path("bindings"));
        } catch (JsonProcessingException exception) {
            LOG.warn("Malformed Wikidata answer: {}", exception.getOriginalMessage());
            return Optional.empty();
        }
    }

    /**
     * IČO — только цифры, дополненные ведущими нулями до восьми; строка без IČO или сайта пропускается.
     */
    private static List<WikidataSite> toSites(JsonNode bindings) {
        List<WikidataSite> sites = new ArrayList<>();
        for (JsonNode row : bindings) {
            String digits = row.path("ico").path("value").asText().replaceAll("\\D", "");
            String url = row.path("site").path("value").asText();
            if (!digits.isEmpty() && digits.length() <= DIGITS && !url.isBlank()) {
                sites.add(new WikidataSite("0".repeat(DIGITS - digits.length()) + digits, url,
                        row.path("item").path("value").asText()));
            }
        }
        return sites;
    }
}
