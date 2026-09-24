package com.roleorienta.worker.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.CoverageAssessment;
import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.worker.coverage.KarriereClient.Listing;
import com.roleorienta.worker.coverage.KarriereClient.Listings;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

/** Проход проверки покрытия (A5, §82): запись оценок; сбой площадки — без записи. */
class CoverageCheckTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    private final CoverageTargetRepository targets = mock(CoverageTargetRepository.class);
    private final CoverageAssessmentRepository assessments = mock(CoverageAssessmentRepository.class);
    private final KarriereClient platform = mock(KarriereClient.class);
    private final CoverageCheck check = new CoverageCheck(
            new CoverageProperties(true, 1000, 3, 24, "https://www.karriere.at/jobs/"),
            targets, assessments, platform, mock(PostgresLeaderLock.class));

    private final Company hitachi = company("hitachi/External");

    @Test
    void writesAssessmentPerAustrianPosting() {
        when(targets.companiesDue(eq("Austria"), eq(NOW.minusSeconds(24 * 3600)), any(Limit.class)))
                .thenReturn(List.of(hitachi));
        when(platform.activeListings("hitachi")).thenReturn(new Listings(List.of(
                new Listing("1", "Sales Operations Manager (f/m/d)", "Hitachi Energy Austria AG")), true));
        when(targets.postingsOf(hitachi, "Austria"))
                .thenReturn(List.of(posting(10L, "Sales Operations Manager (f/m/d)"), posting(11L, "Site Technician")));
        when(assessments.findByPosting_Id(any())).thenReturn(Optional.empty());

        check.checkDue(NOW);

        ArgumentCaptor<CoverageAssessment> saved = ArgumentCaptor.forClass(CoverageAssessment.class);
        verify(assessments, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals(CoverageState.BOTH, saved.getAllValues().get(0).getState());
        assertEquals(CoverageState.SITE_ONLY, saved.getAllValues().get(1).getState());
        assertEquals("karriere.at", saved.getAllValues().get(1).getCheckedPlatforms());
        assertEquals(NOW, saved.getAllValues().get(1).getCheckedAt());
    }

    @Test
    void platformFailureWritesNothing() {
        when(targets.companiesDue(any(), any(), any(Limit.class))).thenReturn(List.of(hitachi));
        when(platform.activeListings("hitachi")).thenThrow(new IllegalStateException("вёрстка"));

        check.checkDue(NOW);

        verify(assessments, never()).save(any());
    }

    @Test
    void searchNameDropsSiteFromSlugLikeName() {
        assertEquals("hitachi", CoverageCheck.searchName(company("hitachi/External")));
        assertEquals("Syneos Health", CoverageCheck.searchName(company("Syneos Health")));
    }

    private static Company company(String name) {
        Company company = new Company();
        company.setName(name);
        return company;
    }

    private static JobPosting posting(long id, String title) {
        JobPosting posting = new JobPosting();
        posting.setId(id);
        posting.setRawTitle(title);
        return posting;
    }
}
