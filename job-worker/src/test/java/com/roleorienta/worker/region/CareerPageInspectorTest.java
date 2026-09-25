package com.roleorienta.worker.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.collect.NicheFilterProperties;
import com.roleorienta.worker.http.SourceHttpClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Карьерная страница и её тип (§95): ссылка на раздел, система найма, разметка JobPosting. */
class CareerPageInspectorTest {

    private static final String HOME = "https://softecon.sk";

    private final SourceHttpClient http = mock(SourceHttpClient.class);
    private final CareerPageInspector inspector = new CareerPageInspector(http, new SourceAdapterRegistry(List.of()),
            new NicheFilterProperties(List.of("Java", "Backend"), List.of("Sales"), 30));

    @Test
    void careerLinkByTextOrPath() {
        assertEquals(Optional.of("https://softecon.sk/kariera"), CareerPageInspector.careerLink(
                "<a href='/o-nas'>O nás</a><a href='/kariera'>Pridaj sa k nám</a>", HOME));
        assertEquals(Optional.of("https://softecon.sk/sk/volne-miesta"), CareerPageInspector.careerLink(
                "<a href='/sk/volne-miesta'>Kariéra</a>", HOME));
        assertEquals(Optional.empty(), CareerPageInspector.careerLink("<a href='/o-nas'>O nás</a>", HOME));
    }

    @Test
    void systemByHost() {
        assertEquals(Optional.of("teamtailor"), CareerPageInspector.systemOf("https://softecon.teamtailor.com/jobs"));
        assertEquals(Optional.of("workday"), CareerPageInspector.systemOf("https://x.wd3.myworkdayjobs.com/en-US/X"));
        assertEquals(Optional.empty(), CareerPageInspector.systemOf("https://softecon.sk/kariera"));
    }

    @Test
    void ownPageWithJobPostingMarkupIsCounted() {
        when(http.getBody("https://softecon.sk/kariera")).thenReturn("""
                <script type="application/ld+json">
                {"@context":"https://schema.org","@graph":[
                  {"@type":"JobPosting","title":"Java Backend Developer"},
                  {"@type":"JobPosting","title":"Sales Manager"},
                  {"@type":"Organization","name":"Softecon"}]}
                </script>""");

        CareerPage page = inspector.inspect(new SiteLocator.Site(HOME, "<a href='/kariera'>Kariéra</a>"));

        assertEquals(new CareerPage("https://softecon.sk/kariera", CareerPage.SCHEMA_ORG, 2, 1), page);
    }

    @Test
    void embeddedSystemAndPlainOwnPage() {
        when(http.getBody("https://softecon.sk/kariera"))
                .thenReturn("<iframe src='https://softecon.recruitee.com/embed'></iframe>");
        assertEquals(new CareerPage("https://softecon.recruitee.com/embed", "recruitee", null, null),
                inspector.inspect(new SiteLocator.Site(HOME, "<a href='/kariera'>Kariéra</a>")));

        when(http.getBody("https://softecon.sk/jobs")).thenReturn("<h1>Hľadáme ľudí</h1>");
        assertEquals(new CareerPage("https://softecon.sk/jobs", CareerPage.OWN_PAGE, null, null),
                inspector.inspect(new SiteLocator.Site(HOME, "<a href='/jobs'>Jobs</a>")));

        assertEquals(new CareerPage(null, CareerPage.NONE, null, null),
                inspector.inspect(new SiteLocator.Site(HOME, "<a href='/o-nas'>O nás</a>")));
    }
}
