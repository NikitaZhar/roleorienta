package com.roleorienta.worker.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.roleorienta.worker.http.SourceHttpClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Сайт компании по названию и номеру юрлица (§95). */
class SiteLocatorTest {

    @Test
    void candidatesWithoutLegalFormAndDiacritics() {
        assertEquals(List.of("softecon.sk", "softecon.com"), SiteLocator.candidates("SOFTECON, s. r. o."));
        assertEquals(List.of("zilinskadata.sk", "zilinska-data.sk", "zilinska.sk", "zilinskadata.com"),
                SiteLocator.candidates("Žilinská Data spol. s r.o."));
        assertEquals(List.of(), SiteLocator.candidates("a.s."));
    }

    @Test
    void icoIsFoundWithSpacesInText() {
        assertTrue(SiteLocator.showsIco("<footer>IČO: 35 800 861, DIČ …</footer>", "35800861"));
        assertFalse(SiteLocator.showsIco("<footer>IČO: 35 800 862</footer>", "35800861"));
    }

    @Test
    void siteIsAcceptedOnlyWhenPageShowsIco() {
        SourceHttpClient http = mock(SourceHttpClient.class);
        doThrow(new IllegalStateException("нет такого сайта")).when(http).getBody(anyString());
        doReturn("<p>Softecon</p>").when(http).getBody("https://softecon.sk");
        doReturn("<p>IČO 12345678</p>").when(http).getBody("https://softecon.sk/kontakt");
        doReturn("<p>Softecon Inc., USA</p>").when(http).getBody("https://softecon.com");

        Optional<SiteLocator.Site> site = new SiteLocator(http).locate("Softecon s.r.o.", "12345678");

        assertEquals("https://softecon.sk", site.orElseThrow().url());
        assertEquals(Optional.empty(), new SiteLocator(http).locate("Softecon s.r.o.", "87654321"));
    }
}
