package com.roleorienta.worker.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.roleorienta.worker.http.SourceHttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Разбор выдачи реестра RPO (§95): действующие название, IČO и город. */
class RpoClientTest {

    @Test
    void currentNameAndIcoFromHistory() {
        RpoClient client = new RpoClient(mock(SourceHttpClient.class), new RegionProperties(false,
                new RegionProperties.Rpo("https://api.statistics.sk/rpo/v1", List.of("programovanie"), List.of("62"),
                        List.of("Bratislava")), 50, 5, 60));
        String body = """
                {"results":[{"id":309597,
                  "identifiers":[{"value":"35800861","validFrom":"2000-11-28"}],
                  "fullNames":[{"value":"Profesia, spol. s r.o.","validFrom":"2000-11-28","validTo":"2023-12-31"},
                               {"value":"Alma Career Slovakia s. r. o.","validFrom":"2024-01-01"}],
                  "addresses":[{"validFrom":"2017-10-01","municipality":"Bratislava"}]},
                 {"id":1,"identifiers":[],"fullNames":[{"value":"Bez IČO"}]}]}
                """;

        assertEquals(List.of(new RpoClient.Hit(309597, "35800861", "Alma Career Slovakia s. r. o.", "Bratislava")),
                client.parseHits(body));
    }
}
