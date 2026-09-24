package com.roleorienta.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.CompanySource;
import com.roleorienta.core.domain.Provider;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.BoardProfile;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/** Имена старых компаний (§83): из описания доски, иначе владелец из slug; временный сбой — без изменений. */
class CompanyNameBackfillTest {

    private final CompanySourceRepository companySources = mock(CompanySourceRepository.class);
    private final SourceAdapterRegistry adapters = mock(SourceAdapterRegistry.class);
    private final SourceAdapter workday = mock(SourceAdapter.class);
    private final CompanyNameBackfill backfill = new CompanyNameBackfill(companySources, adapters,
            new BoardOwnershipProperties(List.of("staffing")), mock(PostgresLeaderLock.class));

    {
        when(adapters.forProviderCode("workday")).thenReturn(workday);
    }

    @Test
    void nameFromBoardDescription() {
        CompanySource link = link(1L, "tobiidynavox/External");
        when(companySources.withSlugNames(any(Limit.class))).thenReturn(List.of(link));
        when(workday.boardProfile(link.getSource())).thenReturn(Optional.of(
                new BoardProfile("tobiidynavox", "At Tobii Dynavox we empower people.")));

        backfill.renameBatch();

        assertEquals("Tobii Dynavox", link.getCompany().getName());
    }

    @Test
    void ownerFromSlugWhenDescriptionHasNoNameOrBoardIsGone() {
        CompanySource noName = link(1L, "dxctechnology/DXCJobs");
        CompanySource gone = link(2L, "salesforce/en-us");
        when(companySources.withSlugNames(any(Limit.class))).thenReturn(List.of(noName, gone));
        when(workday.boardProfile(noName.getSource())).thenReturn(Optional.of(
                new BoardProfile("dxctechnology", "DXC Technology helps global companies.")));
        when(workday.boardProfile(gone.getSource())).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        backfill.renameBatch();

        assertEquals("DXC Technology", noName.getCompany().getName());
        assertEquals("salesforce", gone.getCompany().getName());
    }

    @Test
    void transientFailureKeepsSlug() {
        CompanySource link = link(1L, "lilly/LLY");
        when(companySources.withSlugNames(any(Limit.class))).thenReturn(List.of(link));
        when(workday.boardProfile(link.getSource())).thenThrow(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null));

        backfill.renameBatch();

        assertEquals("lilly/LLY", link.getCompany().getName());
    }

    private static CompanySource link(long companyId, String slug) {
        Provider provider = new Provider();
        provider.setCode("workday");
        Source source = new Source();
        source.setProvider(provider);
        source.setExternalRef(slug);
        Company company = new Company();
        company.setId(companyId);
        company.setName(slug);
        CompanySource link = new CompanySource();
        link.setSource(source);
        link.setCompany(company);
        return link;
    }
}
