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

    private Set<String> countries(String location) {
        return resolver.resolve(List.of(location), "Engineer", "").countries();
    }
}
