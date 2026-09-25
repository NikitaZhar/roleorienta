package com.roleorienta.worker.region;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.http.SourceHttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Клиент реестра юрлиц Словакии RPO (§95): открытый REST API без ключа, данные — CC BY 4.0
 * (https://rpo.minv.sk/rpo-api-doc.html). Поиск ({@code /search}) отдаёт до 500 записей и требует
 * хотя бы один фильтр; основной вид деятельности — только в карточке ({@code /entity/{id}}).
 * Запросы — через {@link SourceHttpClient} (SSRF, темп на домен).
 */
@Component
public class RpoClient {

    private final SourceHttpClient httpClient;
    private final RegionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param httpClient единый HTTP-клиент воркера
     * @param properties адрес API
     */
    public RpoClient(SourceHttpClient httpClient, RegionProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * Юрлицо из выдачи поиска.
     *
     * @param id           идентификатор записи RPO
     * @param ico          номер юрлица (IČO)
     * @param name         действующее название
     * @param municipality город действующего адреса
     */
    public record Hit(long id, String ico, String name, String municipality) {
    }

    /**
     * Основной вид деятельности.
     *
     * @param code  код (SK NACE)
     * @param value название
     */
    public record Activity(String code, String value) {
    }

    /**
     * Действующие юрлица города с видом деятельности, найденным по слову.
     *
     * @param activityTerm слово поиска по основному виду деятельности
     * @param municipality город
     * @return до 500 записей
     */
    public List<Hit> search(String activityTerm, String municipality) {
        String url = properties.rpo().baseUrl() + "/search?onlyActive=true&mainActivity=" + encode(activityTerm)
                + "&addressMunicipality=" + encode(municipality);
        return parseHits(httpClient.getJson(url));
    }

    /**
     * Основной вид деятельности юрлица.
     *
     * @param id идентификатор записи RPO
     * @return вид деятельности; пусто — в карточке его нет
     */
    public Optional<Activity> mainActivity(long id) {
        JsonNode activity = read(httpClient.getJson(properties.rpo().baseUrl() + "/entity/" + id))
                .path("statisticalCodes").path("mainActivity");
        String code = activity.path("code").asText("");
        return code.isEmpty() ? Optional.empty() : Optional.of(new Activity(code, activity.path("value").asText("")));
    }

    /** Разбор выдачи поиска: действующие (последние) идентификатор, название и адрес. */
    List<Hit> parseHits(String body) {
        List<Hit> hits = new ArrayList<>();
        for (JsonNode result : read(body).path("results")) {
            String ico = current(result.path("identifiers"), "value");
            String name = current(result.path("fullNames"), "value");
            if (!ico.isEmpty() && !name.isEmpty()) {
                hits.add(new Hit(result.path("id").asLong(), ico, name, current(result.path("addresses"), "municipality")));
            }
        }
        return hits;
    }

    /** Значение поля у действующей записи истории (без {@code validTo}), иначе у последней. */
    private static String current(JsonNode history, String field) {
        JsonNode chosen = null;
        for (JsonNode entry : history) {
            if (entry.path("validTo").isMissingNode() || entry.path("validTo").isNull()) {
                chosen = entry;
            }
        }
        if (chosen == null && history.size() > 0) {
            chosen = history.get(history.size() - 1);
        }
        return chosen == null ? "" : chosen.path(field).asText("");
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RPO: ответ не разобран: " + exception.getOriginalMessage(), exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
