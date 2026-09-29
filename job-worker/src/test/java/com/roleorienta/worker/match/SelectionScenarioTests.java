package com.roleorienta.worker.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Приёмочный сценарий 10 (бизнес-описание §7.4-А) на размеченном корпусе {@code selection-corpus.json}:
 * смысловое совпадение позиции включается, другая специализация — нет; явное несоответствие страны
 * или формата исключает; неоднозначные сведения — включение с пометкой. Словарь позиций и регионы —
 * настоящие; города — небольшой набор вместо GeoNames. Каждое расхождение с разметкой перечисляется.
 */
class SelectionScenarioTests {

    private static final Set<String> SEARCH_COUNTRIES = Set.of("SK");
    private static final Map<String, String> CITIES = Map.of("bratislava", "SK", "kosice", "SK", "zilina", "SK",
            "vienna", "AT", "berlin", "DE", "prague", "CZ");

    private final PositionDictionary dictionary = new PositionDictionary();
    private final LocationResolver resolver = new LocationResolver(CITIES, new RemoteRegions());

    /**
     * Все случаи корпуса оцениваются точно по разметке.
     *
     * @throws IOException корпус не читается
     */
    @Test
    void scenario10SelectionMatchesLabelledCorpus() throws IOException {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode example : corpus().path("cases")) {
            String title = example.path("title").asText();
            String text = example.path("text").asText();
            JsonNode search = example.path("search");
            boolean position = PositionMatcher.match(dictionary, title, text).stream()
                    .anyMatch(match -> match.code().equals(search.path("position").asText()));
            LocationResolver.LocationFacts facts = resolver.resolve(List.of(example.path("location").asText()), title,
                    text);
            Fit country = Fit.country(facts.countries(), facts.uncertain(), SEARCH_COUNTRIES);
            Fit format = Fit.format(facts.format(),
                    search.path("format").isNull() ? null : WorkFormat.valueOf(search.path("format").asText()));
            String actual = position + "/" + country + "/" + format;
            JsonNode expect = example.path("expect");
            String expected = expect.path("position").asBoolean() + "/" + expect.path("country").asText() + "/"
                    + expect.path("format").asText();
            if (!actual.equals(expected)) {
                mismatches.add(title + " @ " + example.path("location").asText() + ": expected " + expected
                        + ", got " + actual);
            }
        }

        assertThat(mismatches).isEmpty();
    }

    private static JsonNode corpus() throws IOException {
        try (InputStream input = SelectionScenarioTests.class.getResourceAsStream("/selection-corpus.json")) {
            return new ObjectMapper().readTree(input);
        }
    }
}
