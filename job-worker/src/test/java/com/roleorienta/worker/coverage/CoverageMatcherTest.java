package com.roleorienta.worker.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.worker.coverage.CoverageMatcher.Verdict;
import com.roleorienta.worker.coverage.KarriereClient.Listing;
import com.roleorienta.worker.coverage.KarriereClient.Listings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Строгое сопоставление с выдачей karriere.at (A5, §82) — случаи из разведки 2026-09-24. */
class CoverageMatcherTest {

    private static final Listings HITACHI = new Listings(List.of(
            new Listing("10030772", "Sales Operations Manager (f/m/d)", "Hitachi Energy Austria AG"),
            new Listing("10026851", "Software Project Manager Railway Systems (f/m/d)", "Hitachi Rail Austria GmbH"),
            new Listing("7863547", "Manager Corporate Development (all genders)", "LINDLPOWER Personalmanagement")),
            true);

    private static Verdict verdict(String title, String employer, Listings listings) {
        return CoverageMatcher.verdict(title, employer, listings, "2026-09-24");
    }

    @Test
    void sameTitleSameEmployerIsBoth() {
        assertEquals(CoverageState.BOTH, verdict("Sales Operations Manager (f/m/d)", "hitachi", HITACHI).state());
        assertEquals(CoverageState.BOTH, verdict("sales operations manager (m/w/d)", "Hitachi", HITACHI).state());
    }

    @Test
    void nearTitleIsUnknownNotHidden() {
        Verdict near = verdict("Software Project Manager for Railway Systems (f/m/d)", "hitachi", HITACHI);
        assertEquals(CoverageState.UNKNOWN, near.state());
        assertTrue(near.reason().startsWith("похожая вакансия"), near.reason());
    }

    @Test
    void sameTitleOtherCompanyIsUnknown() {
        Listings ecolab = new Listings(List.of(
                new Listing("10031369", "CAD Administrator (m/w/d) – Anlagenbau", "Ovivo Aqua Austria GmbH")), true);
        Verdict foreign = verdict("CAD Administrator (m/w/d) – Anlagenbau", "ecolab", ecolab);
        assertEquals(CoverageState.UNKNOWN, foreign.state());
        assertTrue(foreign.reason().contains("Ovivo Aqua Austria GmbH"), foreign.reason());
    }

    @Test
    void notFoundIsSiteOnlyWithDateOrUnknownWhenIncomplete() {
        Verdict hidden = verdict("Field Service Engineer (w/m/d)", "hitachi", HITACHI);
        assertEquals(CoverageState.SITE_ONLY, hidden.state());
        assertEquals("не найдена на karriere.at при проверке 2026-09-24", hidden.reason());

        Listings partial = new Listings(HITACHI.items(), false);
        assertEquals(CoverageState.UNKNOWN, verdict("Field Service Engineer (w/m/d)", "hitachi", partial).state());
    }

    @Test
    void normalizeDropsGenderMarksAndPunctuation() {
        assertEquals("cad administrator anlagenbau", CoverageMatcher.normalize("CAD Administrator (m/w/d) – Anlagenbau"));
        assertEquals("site technician", CoverageMatcher.normalize("Site Technician (w/m/*)"));
    }
}
