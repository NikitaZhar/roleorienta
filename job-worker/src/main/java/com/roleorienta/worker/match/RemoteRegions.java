package com.roleorienta.worker.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Регионы удалённой работы — данные с версией (файл {@code regions.json}; технический документ §6):
 * «EU», «CEE», «DACH», «worldwide» … → множество стран; «*» — без ограничения.
 */
@Component
public class RemoteRegions {

    private static final String RESOURCE = "/regions.json";

    private final String version;
    private final Map<String, Set<String>> countriesByRegion;

    /**
     * Загружает регионы из ресурса приложения.
     */
    public RemoteRegions() {
        JsonNode regions = read();
        this.version = regions.path("version").asText();
        Map<String, Set<String>> loaded = new HashMap<>();
        regions.path("regions").fields().forEachRemaining(region -> {
            Set<String> countries = new HashSet<>();
            region.getValue().forEach(country -> countries.add(country.asText()));
            loaded.put(PositionDictionary.normalize(region.getKey()).strip(), Set.copyOf(countries));
        });
        this.countriesByRegion = Map.copyOf(loaded);
    }

    public String version() {
        return version;
    }

    /**
     * @param normalizedName нормализованное название территории
     * @return страны региона; {@code null} — не регион
     */
    Set<String> countries(String normalizedName) {
        return countriesByRegion.get(normalizedName);
    }

    private static JsonNode read() {
        try (InputStream input = RemoteRegions.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Remote regions " + RESOURCE + " are missing");
            }
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("Remote regions " + RESOURCE + " are not readable", exception);
        }
    }
}
