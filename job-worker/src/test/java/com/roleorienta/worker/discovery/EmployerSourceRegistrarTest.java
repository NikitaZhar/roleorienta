package com.roleorienta.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.CompanySource;
import com.roleorienta.core.domain.Provider;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceKind;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.core.domain.VerifiedBy;
import com.roleorienta.worker.discovery.EmployerSourceRegistrar.Registration;
import com.roleorienta.worker.scheduling.SourceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Материализация источника из уверенного кандидата (§35): создание и дедуп. */
class EmployerSourceRegistrarTest {

    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final SourceRepository sources = mock(SourceRepository.class);
    private final CompanySourceRepository companySources = mock(CompanySourceRepository.class);
    private final EmployerSourceRegistrar registrar =
            new EmployerSourceRegistrar(providers, companies, sources, companySources);

    @Test
    void createsActiveSourceWithAutoVerifiedLink() {
        when(sources.findByProvider_CodeAndExternalRef("greenhouse", "acme"))
                .thenReturn(Optional.empty());
        when(providers.findByCode("greenhouse")).thenReturn(Optional.empty());
        when(providers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        registrar.register("greenhouse", "acme", "http://stub", "Acme");

        verify(providers).save(any());

        ArgumentCaptor<Source> sourceCaptor = ArgumentCaptor.forClass(Source.class);
        verify(sources).save(sourceCaptor.capture());
        Source source = sourceCaptor.getValue();
        assertEquals(SourceState.ACTIVE, source.getState());
        assertEquals(SourceKind.COMPANY_BOARD, source.getKind());
        assertEquals("acme", source.getExternalRef());
        assertEquals("http://stub", source.getBaseUrl());

        ArgumentCaptor<Company> companyCaptor = ArgumentCaptor.forClass(Company.class);
        verify(companies).save(companyCaptor.capture());
        assertEquals("Acme", companyCaptor.getValue().getName());

        ArgumentCaptor<CompanySource> linkCaptor = ArgumentCaptor.forClass(CompanySource.class);
        verify(companySources).save(linkCaptor.capture());
        assertEquals(VerifiedBy.AUTO, linkCaptor.getValue().getVerifiedBy());
    }

    @Test
    void reusesExistingSourceWithoutCreating() {
        Source existing = new Source();
        existing.setId(99L);
        when(sources.findByProvider_CodeAndExternalRef("greenhouse", "acme"))
                .thenReturn(Optional.of(existing));

        Registration registration = registrar.register("greenhouse", "acme", "http://stub", "Acme");

        assertEquals(99L, registration.sourceId());
        verify(companies, never()).save(any());
        verify(sources, never()).save(any());
        verify(companySources, never()).save(any());
        verify(providers, never()).save(any());
    }
}
