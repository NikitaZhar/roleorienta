package com.roleorienta.worker.adapters.personio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.BoardProfile;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.adapters.MarketScope;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.http.SourceHttpClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Адаптер Personio (§91): разбор XML-ленты, офисы для гейта, отбор по рынку, деталь, владелец. */
class PersonioAdapterTest {

    private static final String FEED_URL = "https://towa.jobs.personio.de/xml";

    /** Структура — как у настоящей ленты (towa.jobs.personio.de, 2026-09-25), значения сокращены. */
    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <workzag-jobs>
              <position>
                <id>1552584</id>
                <subcompany>TOWA GmbH</subcompany>
                <office>Wien</office>
                <additionalOffices><office>Bregenz</office><office>Wien</office></additionalOffices>
                <department>Development</department>
                <name>Java Developer (m/w/d)</name>
                <jobDescriptions>
                  <jobDescription>
                    <name>Deine Aufgaben</name>
                    <value><![CDATA[<p>Backend mit <b>Spring</b></p>]]></value>
                  </jobDescription>
                  <jobDescription>
                    <name>Dein Profil</name>
                    <value><![CDATA[<ul><li>Java 21</li></ul>]]></value>
                  </jobDescription>
                </jobDescriptions>
                <employmentType>permanent</employmentType>
                <createdAt>2026-09-20T10:39:53+00:00</createdAt>
              </position>
              <position>
                <id>77</id>
                <subcompany>TOWA Deutschland GmbH</subcompany>
                <office>München</office>
                <name>Sales Manager</name>
                <jobDescriptions></jobDescriptions>
                <createdAt>kein Datum</createdAt>
              </position>
            </workzag-jobs>
            """;

    private final SourceHttpClient httpClient = mock(SourceHttpClient.class);
    private final PersonioAdapter adapter = new PersonioAdapter(httpClient);

    private static Source board() {
        Source source = new Source();
        source.setExternalRef("towa");
        source.setBaseUrl("https://towa.jobs.personio.de/");
        return source;
    }

    @Test
    void parsesPositionsWithOfficesAndDescription() {
        List<PersonioAdapter.Position> positions = PersonioAdapter.parse(FEED);

        assertEquals(2, positions.size());
        PersonioAdapter.Position first = positions.get(0);
        assertEquals("1552584", first.id());
        assertEquals("Java Developer (m/w/d)", first.name());
        assertEquals(List.of("Wien", "Bregenz"), first.offices());
        assertEquals("Deine Aufgaben: Backend mit Spring\nDein Profil: Java 21", first.description());
        assertNull(positions.get(1).description());
    }

    @Test
    void listCountsOfficesOfWholeFeedAndKeepsOnlyMarketPostings() {
        when(httpClient.getBody(FEED_URL)).thenReturn(FEED);
        MarketScope austria = new MarketScope(country -> false, Set.of("Wien", "Bregenz")::contains);

        PostingsPage all = adapter.listPostings(board(), null);
        PostingsPage market = adapter.listPostings(board(), null, austria);

        assertEquals(Map.of("Wien", 1, "Bregenz", 1, "München", 1), all.locationCounts());
        assertEquals(2, all.postings().size());
        assertEquals(1, market.postings().size());
        assertEquals("https://towa.jobs.personio.de/job/1552584", market.postings().get(0).url());
        assertEquals("Wien", market.postings().get(0).rawLocation());
        assertTrue(adapter.reportsCountries());
    }

    @Test
    void detailComesFromFeed() {
        when(httpClient.getBody(FEED_URL)).thenReturn(FEED);

        FetchedPosting detail = adapter.getPosting(board(), "1552584");

        assertEquals("Wien", detail.location().raw());
        assertEquals(List.of("Bregenz"), detail.location().additional());
        assertEquals(LocalDate.of(2026, 9, 20), detail.postedOn());
        assertEquals(FetchedPosting.SourcePay.NONE, detail.pay());
        assertThrows(IllegalStateException.class, () -> adapter.getPosting(board(), "404"));
    }

    @Test
    void boardProfileNamesLegalEntitiesOfFeed() {
        when(httpClient.getBody(FEED_URL)).thenReturn(FEED);

        assertEquals(Optional.of(new BoardProfile("towa", "TOWA GmbH; TOWA Deutschland GmbH")),
                adapter.boardProfile(board()));
    }

    @Test
    void doctypeIsRejected() {
        assertThrows(IllegalStateException.class, () -> PersonioAdapter.parse(
                "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><workzag-jobs/>"));
    }

    @Test
    void boardFromAnyPageOfAccount() {
        assertEquals(Optional.of(new PersonioBoard("towa", "https://towa.jobs.personio.de")),
                PersonioBoard.fromCareerUrl("https://towa.jobs.personio.de/job/1552584?language=de"));
        assertEquals(Optional.empty(), PersonioBoard.fromCareerUrl("https://jobs.personio.de/"));
        assertEquals(Optional.empty(), PersonioBoard.fromCareerUrl("https://towa.personio.de/"));
    }
}
