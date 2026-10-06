package com.roleorienta.worker.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Категория числа сотрудников компании из RÚZ Open API (https://www.registeruz.sk/cruz-public/home/api; данные
 * CC0): {@code uctovne-jednotky?zmenene-od=2000-01-01&ico=<IČO>} — id учётных единиц с этим IČO, берётся последний
 * (самый новый); {@code uctovna-jednotka?id=<id>} — карточка, поле {@code velkostOrganizacie} — код категории.
 * Код переводится в нижнюю границу числа сотрудников по классификатору RÚZ {@code velkosti-organizacie}.
 */
@Component
public class RuzClient {

    /** Нижняя граница числа сотрудников по коду категории RÚZ; «00 nezistený» — нет в таблице. */
    static final Map<String, Integer> EMPLOYEES_MIN = Map.ofEntries(Map.entry("01", 0), Map.entry("02", 1),
            Map.entry("03", 2), Map.entry("04", 3), Map.entry("05", 5), Map.entry("06", 10), Map.entry("07", 20),
            Map.entry("11", 25), Map.entry("12", 50), Map.entry("21", 100), Map.entry("22", 150),
            Map.entry("23", 200), Map.entry("24", 250), Map.entry("25", 500), Map.entry("31", 1000),
            Map.entry("32", 2000), Map.entry("33", 3000), Map.entry("34", 4000), Map.entry("35", 5000),
            Map.entry("36", 10000), Map.entry("37", 20000), Map.entry("38", 30000));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(RuzClient.class);

    private final ExternalHttpClient httpClient;
    private final CompanySizeProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес API
     */
    public RuzClient(ExternalHttpClient httpClient, CompanySizeProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * @param registrationNumber IČO
     * @return ответ RÚZ; пусто — RÚZ не ответил или ответ не разобран (спросить позже)
     */
    public Optional<RuzSize> size(String registrationNumber) {
        Optional<JsonNode> units = get("/uctovne-jednotky?zmenene-od=2000-01-01&ico=" + registrationNumber);
        if (units.isEmpty()) {
            return Optional.empty();
        }
        JsonNode ids = units.get().path("id");
        if (!ids.isArray() || ids.isEmpty()) {
            return Optional.of(RuzSize.UNKNOWN);
        }
        Optional<JsonNode> card = get("/uctovna-jednotka?id=" + ids.get(ids.size() - 1).asLong());
        return card.map(node -> new RuzSize(EMPLOYEES_MIN.get(node.path("velkostOrganizacie").asText())));
    }

    private Optional<JsonNode> get(String path) {
        HttpResult result = httpClient.get(URI.create(properties.baseUrl() + path));
        if (!(result instanceof HttpResult.Success success)) {
            LOG.warn("RÚZ {} not read: {}", path, result);
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(success.body()));
        } catch (JsonProcessingException exception) {
            LOG.warn("Malformed RÚZ answer for {}: {}", path, exception.getOriginalMessage());
            return Optional.empty();
        }
    }

    /**
     * Категория числа сотрудников.
     *
     * @param employeesMin нижняя граница; {@code null} — не указана или учётной единицы нет
     */
    public record RuzSize(Integer employeesMin) {

        /** Учётной единицы нет или категория не указана. */
        public static final RuzSize UNKNOWN = new RuzSize(null);
    }
}
