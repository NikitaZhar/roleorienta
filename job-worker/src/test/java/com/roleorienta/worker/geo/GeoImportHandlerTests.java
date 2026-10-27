package com.roleorienta.worker.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Разбор файла GeoNames (§210): название и латинское название — у всех городов; другие названия — у городов Словакии и у
 * городов от 500 000 жителей (не короче 4 букв); у малых городов других стран — нет.
 */
class GeoImportHandlerTests {

    @Test
    void takesAlternateNamesOfSlovakAndLargeCities() throws IOException {
        String file = line("Warsaw", "Warsaw", "Warszawa,Varsovie,WAW", "PL", 1_702_139)
                + line("Bengaluru", "Bengaluru", "Bangalore", "IN", 8_443_675)
                + line("Bratislava", "Bratislava", "Pozsony,BA", "SK", 423_737)
                + line("Nitra", "Nitra", "Neutra", "SK", 78_353)
                + line("Lublin", "Lublin", "Lyublin", "PL", 339_784);

        Map<String, GeoCity> cities = GeoImportHandler.parse(new BufferedReader(new StringReader(file)));

        assertThat(cities).containsKeys("warsaw|PL", "warszawa|PL", "varsovie|PL", "bangalore|IN", "pozsony|SK",
                "ba|SK", "neutra|SK", "lublin|PL");
        assertThat(cities).doesNotContainKeys("waw|PL", "lyublin|PL");
    }

    /** Строка файла GeoNames: id, название, латинское, другие названия, …, страна (8), …, население (14). */
    private static String line(String name, String ascii, String alternates, String country, long population) {
        String[] fields = new String[19];
        Arrays.fill(fields, "");
        fields[0] = "1";
        fields[1] = name;
        fields[2] = ascii;
        fields[3] = alternates;
        fields[8] = country;
        fields[14] = String.valueOf(population);
        return String.join("\t", fields) + "\n";
    }
}
