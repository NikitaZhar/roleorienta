package com.roleorienta.worker.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roleorienta.worker.coverage.KarriereClient.Listing;
import com.roleorienta.worker.coverage.KarriereClient.Listings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Разбор выдачи karriere.at (A5, §82) — по вёрстке, снятой разведкой 2026-09-24. */
class KarriereClientTest {

    private static final String ITEM = """
            <li class="m-jobsList__item"><div class="m-jobsListItem m-jobsListItem--active">
              <h2 class="m-jobsListItem__title"><a class="m-jobsListItem__titleLink"
                 href="https://www.karriere.at/jobs/%s" target="_blank">%s</a></h2>
              <div class="m-jobsListItem__company"><a class="m-jobsListItem__companyName"
                 href="https://www.karriere.at/f/x">%s</a></div>
            </div></li>""";

    private static String page(String header, String items) {
        return "<html><body><h1 class=\"m-jobsListHeader__title\"><span>" + header + "</span></h1>"
                + "<div class=\"m-jobsSearchList__activeJobs\"><ol class=\"m-jobsList\">" + items
                + "<li class=\"m-jobsList__alarmDisruptor m-jobsList__item\"><div>Jobalarm</div></li>"
                + "</ol></div><h2>Diese Jobs hast du verpasst</h2><ol class=\"m-jobsList\">"
                + ITEM.formatted("1", "Archived Job", "Old GmbH") + "</ol></body></html>";
    }

    @Test
    void parsesActiveJobsOnlyAndSkipsAdvert() {
        Listings listings = KarriereClient.parse(page("2 Hitachi Jobs",
                ITEM.formatted("10030772", "Sales Operations Manager (f/m/d)", "Hitachi Energy Austria AG")
                        + ITEM.formatted("7863547", "Manager Corporate Development", "LINDLPOWER Personalmanagement")));

        assertEquals(List.of(
                new Listing("10030772", "Sales Operations Manager (f/m/d)", "Hitachi Energy Austria AG"),
                new Listing("7863547", "Manager Corporate Development", "LINDLPOWER Personalmanagement")),
                listings.items());
        assertTrue(listings.complete());
    }

    @Test
    void emptyResultIsCompleteAndMoreAnnouncedThanParsedIsNot() {
        Listings empty = KarriereClient.parse(page("Accenture Jobs", ""));
        assertTrue(empty.items().isEmpty());
        assertTrue(empty.complete());

        assertFalse(KarriereClient.parse(page("25 Hitachi Jobs",
                ITEM.formatted("1", "A", "Hitachi"))).complete());
    }

    @Test
    void unexpectedPageIsAnError() {
        assertThrows(IllegalStateException.class, () -> KarriereClient.parse("<html><body>Wartung</body></html>"));
    }

    @Test
    void searchSlug() {
        assertEquals("syneos-health", KarriereClient.slug("Syneos Health"));
        assertEquals("tobii-dynavox", KarriereClient.slug(" Tobii  Dynavox "));
    }
}
