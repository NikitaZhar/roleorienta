package com.roleorienta.worker.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Места Workday из замечания владельца (§210): код места через подчёркивание и названия городов на языке страны.
 */
class LocationResolverTests {

    private final LocationResolver resolver = new LocationResolver(Map.of("katowice", "PL", "warszawa", "PL",
            "bangalore", "IN"), new RemoteRegions());

    @Test
    void resolvesWorkdayPlaceCodesAndLocalNames() {
        assertThat(countries("PB_Cen_Katowice (ul. Sokolska 34); PB_Cen_Warszawa (ul. Puławska 2)")).containsOnly("PL");
        assertThat(countries("INBLR02 - Bangalore - Milesstone Buildcon")).containsOnly("IN");
        assertThat(countries("Warszawa")).containsOnly("PL");
    }

    /**
     * Город страны сбора в названии или карточке — эта страна (§299); город мира или имя человека — нет; страна,
     * распознанная по месту, не меняется.
     */
    @Test
    void homePlaceInTitleOrCardGivesCollectionCountry() {
        Map<String, String> home = Map.of("kosice", "SK", "banska bystrica", "SK", "nitra", "SK");
        assertThat(LocationResolver.homeCountries(home,
                "Reliability Testing Engineer for MinebeaMitsumi development centre Košice (m/f/d)", List.of()))
                .containsOnly("SK");
        assertThat(LocationResolver.homeCountries(home, "Farmaceut/ka - nová lekáreň, Banská Bystrica", List.of()))
                .containsOnly("SK");
        assertThat(LocationResolver.homeCountries(home, "Farmaceut/ka",
                java.util.Arrays.asList(null, "TPP Lokalita Nitra Základná mzda od 1790 €"))).containsOnly("SK");
        assertThat(LocationResolver.homeCountries(home, "Milan Antal", List.of())).isEmpty();
        assertThat(LocationResolver.homeCountries(home, "Bystrica", List.of())).isEmpty();
        assertThat(resolver.resolve(List.of("Warszawa"), "Engineer Košice", "")
                .orHomeCountries(LocationResolver.homeCountries(home, "Engineer Košice", List.of("Warszawa")))
                .countries()).containsOnly("PL");
    }

    private Set<String> countries(String location) {
        return resolver.resolve(List.of(location), "Engineer", "").countries();
    }
}
