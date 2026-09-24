package com.roleorienta.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.DiscoveryConfidence;
import com.roleorienta.core.domain.EmployerCandidate;
import com.roleorienta.core.domain.EmployerCandidateState;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.discovery.EmployerSourceRegistrar.Registration;
import com.roleorienta.worker.jobs.JobMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Гейт уверенности обнаружения (§35): HIGH → авто-подключение источника и кандидат
 * CONFIRMED; LOW/NONE → очередь на подтверждение (PENDING); дедуп. Гейт рынка (§56):
 * публикации на рынке → HIGH; нет → OUT_OF_MARKET; распределение неизвестно (как у
 * Greenhouse в первых тестах) или рынок не задан → прежнее правило. Реестр адаптеров,
 * адаптер, регистратор источника и репозиторий — заглушки (без сети и БД).
 */
class DiscoverEmployerJobHandlerTest {

    private final SourceAdapterRegistry registry = mock(SourceAdapterRegistry.class);
    private final SourceAdapter adapter = mock(SourceAdapter.class);
    private final EmployerCandidateRepository repository = mock(EmployerCandidateRepository.class);
    private final EmployerSourceRegistrar registrar = mock(EmployerSourceRegistrar.class);
    private final DiscoverEmployerJobHandler handler =
            new DiscoverEmployerJobHandler(registry, repository, registrar,
                    new DiscoveryMarketProperties(List.of("Slovakia", "Austria"),
                            List.of("Slovakia", "Austria", "Bratislava", "Wien", "Graz", "AUT"), List.of("Vienna")));

    private static final String PAYLOAD =
            "{\"providerCode\":\"greenhouse\",\"slug\":\"acme\",\"baseUrl\":\"http://stub\"}";

    private JobMessage message() {
        return new JobMessage("key-1", "DISCOVER_EMPLOYER", PAYLOAD);
    }

    @Test
    void highConfidenceAutoConnectsSource() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(
                List.of(new DiscoveredPosting("1", "http://stub/1", "Dev")), null));
        when(registrar.register("greenhouse", "acme", "http://stub", "acme"))
                .thenReturn(new Registration(5L, 20L));

        handler.handle(message());

        verify(registrar).register("greenhouse", "acme", "http://stub", "acme");
        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.CONFIRMED, saved.getState());
        assertEquals(DiscoveryConfidence.HIGH, saved.getConfidence());
        assertEquals(20L, saved.getSourceId());
        assertEquals(5L, saved.getCompanyId());
        assertEquals(1, saved.getPostingCount());
    }

    @Test
    void emptyFeedGoesToQueueAsLow() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(List.of(), null));

        handler.handle(message());

        verify(registrar, never()).register(any(), any(), any(), any());
        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.PENDING, saved.getState());
        assertEquals(DiscoveryConfidence.LOW, saved.getConfidence());
        assertNull(saved.getSourceId());
    }

    @Test
    void unreadableFeedGoesToQueueAsNone() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenThrow(new RuntimeException("boom"));

        handler.handle(message());

        verify(registrar, never()).register(any(), any(), any(), any());
        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.PENDING, saved.getState());
        assertEquals(DiscoveryConfidence.NONE, saved.getConfidence());
        assertTrue(saved.getReason().contains("boom"));
    }

    private static final List<DiscoveredPosting> ONE =
            List.of(new DiscoveredPosting("1", "http://stub/1", "Dev"));

    @Test
    void marketPostingsGiveHighAndAutoConnect() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(ONE, null,
                java.util.Map.of("United States of America", 173, "Austria", 2)));
        when(registrar.register(any(), any(), any(), any())).thenReturn(new Registration(5L, 20L));

        handler.handle(message());

        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.CONFIRMED, saved.getState());
        assertEquals(DiscoveryConfidence.HIGH, saved.getConfidence());
        assertTrue(saved.getReason().contains("2 из 175"), saved.getReason());
    }

    @Test
    void noMarketPostingsGoOutOfMarketWithoutQueue() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(ONE, null,
                java.util.Map.of("United States of America", 173, "India", 28, "Poland", 15, "Czechia", 1)));

        handler.handle(message());

        verify(registrar, never()).register(any(), any(), any(), any());
        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.OUT_OF_MARKET, saved.getState());
        assertEquals(DiscoveryConfidence.LOW, saved.getConfidence());
        assertTrue(saved.getReason().contains("United States of America 173, India 28, Poland 15"),
                saved.getReason());
    }

    @Test
    void providerThatReportsCountriesButSentNoneIsNotConnectedBlindly() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.reportsCountries()).thenReturn(true);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(ONE, null)); // ни стран, ни локаций

        handler.handle(message());

        verify(registrar, never()).register(any(), any(), any(), any());
        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.PENDING, saved.getState());
        assertEquals(DiscoveryConfidence.LOW, saved.getConfidence());
        assertTrue(saved.getReason().contains("рынок не проверен"), saved.getReason());
    }

    @Test
    void withoutFacetsTenantIsJudgedByPostingLocationsOfFirstPage() {
        // Стенд §72: tamus/* — ни фасета стран, ни фасета локаций; у вакансий — «College Station, TX».
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.reportsCountries()).thenReturn(true);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(List.of(
                new DiscoveredPosting("1", "http://stub/1", "Lab Technician", "College Station, TX"),
                new DiscoveredPosting("2", "http://stub/2", "Advisor", "College Station, TX"),
                new DiscoveredPosting("3", "http://stub/3", "Nurse", "2 Locations")), null));

        handler.handle(message());

        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.OUT_OF_MARKET, saved.getState());
        assertTrue(saved.getReason().startsWith("по вакансиям первой страницы"), saved.getReason());
        assertTrue(saved.getReason().contains("College Station, TX 2"), saved.getReason());
    }

    @Test
    void onlyLocationSummariesLeaveMarketUnchecked() {
        // «2 Locations» страны не называет — рынок по-прежнему не проверен, кандидат ждёт человека.
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.reportsCountries()).thenReturn(true);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(List.of(
                new DiscoveredPosting("1", "http://stub/1", "Engineer", "2 Locations")), null));

        handler.handle(message());

        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.PENDING, saved.getState());
        assertTrue(saved.getReason().contains("рынок не проверен"), saved.getReason());
    }

    @Test
    void singleCountryTenantIsJudgedByLocations() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.reportsCountries()).thenReturn(true);
        // Реальная форма у тенанта aaaie (§59): стран нет, локации — штаты США.
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(ONE, null, java.util.Map.of(),
                java.util.Map.of("Arizona - Home Teleworkers", 12, "Alabama - Home Teleworkers", 9)));

        handler.handle(message());

        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.OUT_OF_MARKET, saved.getState());
        assertTrue(saved.getReason().contains("Arizona - Home Teleworkers 12"), saved.getReason());
    }

    @Test
    void marketLocationWithoutCountryFacetGivesHigh() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.reportsCountries()).thenReturn(true);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(ONE, null, java.util.Map.of(),
                java.util.Map.of("AUT.9.Vienna", 4, "Brno", 2)));
        when(registrar.register(any(), any(), any(), any())).thenReturn(new Registration(5L, 20L));

        handler.handle(message());

        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.CONFIRMED, saved.getState());
        assertTrue(saved.getReason().contains("на рынке: 4 из 6"), saved.getReason());
        assertTrue(saved.getReason().contains("совпали: AUT.9.Vienna 4"), "причина называет совпавшие локации");
    }

    /** Реальные локации из прогона по ~1000 доскам Workday (§61). */
    @Test
    void viennaInTheUsIsNotTheMarket() {
        DiscoveryMarketProperties m = new DiscoveryMarketProperties(List.of("Slovakia", "Austria"),
                List.of("Slovakia", "Austria", "Bratislava", "Wien", "Graz", "Salzburg", "Styria", "AUT"),
                List.of("Vienna"));
        java.util.Map<String, Integer> locations = new java.util.LinkedHashMap<>();
        locations.put("Vienna, VA", 1);
        locations.put("VA - Vienna", 6);
        locations.put("US-VA-Vienna", 10);
        locations.put("Vienna, Virginia", 1);
        locations.put("JD: 00676 Vienna, West Virginia (Grand Central Mall)", 2);
        locations.put("Store 01111 Vienna, WV-Vienna,WV 26105", 1);
        locations.put("Store 06440 Vienna GA", 1);
        locations.put("Vienna, VA, USA (Pike 7 Plaza - J.Crew Factory)", 4);

        DiscoveryMarketProperties.LocationMatch us = m.matchLocations(locations);
        assertEquals(0, us.marketCount());
        assertEquals(0, us.ambiguousCount(), "с маркером США — не рынок и не неоднозначно");

        DiscoveryMarketProperties.LocationMatch at = m.matchLocations(java.util.Map.of(
                "Vienna, Austria", 3, "AUT-Vienna Am Europlatz 5", 1, "Remote - Austria", 4,
                "Graz, Styria", 1, "SV-Bratislava", 1, "Vienna", 2));
        assertEquals(10, at.marketCount());
        assertEquals(2, at.ambiguousCount(), "голая «Vienna» — неоднозначно");
    }

    @Test
    void onlyAmbiguousLocationsGoToManualReview() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.reportsCountries()).thenReturn(true);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(ONE, null, java.util.Map.of(),
                java.util.Map.of("Vienna", 4, "Charlotte", 700)));

        handler.handle(message());

        verify(registrar, never()).register(any(), any(), any(), any());
        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.PENDING, saved.getState());
        assertTrue(saved.getReason().contains("неоднозначные локации (4 из 704): Vienna 4"), saved.getReason());
    }

    @Test
    void locationTermsMatchWholeWordsOnly() {
        DiscoveryMarketProperties m = new DiscoveryMarketProperties(List.of("Austria"),
                List.of("Austria", "Wien", "Košice", "AUT"), List.of());

        assertEquals(1, m.marketLocationCount(java.util.Map.of("Bratislava, KOŠICE office", 1)));
        assertEquals(2, m.marketLocationCount(java.util.Map.of("AUT.9.Vienna", 2)));
        assertEquals(0, m.marketLocationCount(java.util.Map.of("Autauga County, AL", 5, "Wiener Neustadt Str, Berlin", 1)),
                "AUT внутри слова и Wien внутри Wiener не считаются");
    }

    @Test
    void marketMatchIgnoresCase() {
        assertEquals(3, DiscoveryMarketProperties.ofCountries(List.of(" slovakia ", "AUSTRIA"))
                .marketCount(java.util.Map.of("Slovakia", 1, "Austria", 2, "Germany", 9)));
    }

    @Test
    void disabledMarketKeepsOldRule() {
        DiscoverEmployerJobHandler noMarket =
                new DiscoverEmployerJobHandler(registry, repository, registrar, DiscoveryMarketProperties.ofCountries(List.of()));
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(ONE, null,
                java.util.Map.of("United States of America", 173)));
        when(registrar.register(any(), any(), any(), any())).thenReturn(new Registration(5L, 20L));

        noMarket.handle(message());

        assertEquals(EmployerCandidateState.CONFIRMED, capture().getState());
    }

    @Test
    void transientSourceFailureIsRetriedNotRecorded() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenThrow(
                new com.roleorienta.worker.http.SourceBackoffException("myworkdayjobs.com", java.time.Duration.ofSeconds(90)));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.roleorienta.worker.http.SourceBackoffException.class, () -> handler.handle(message()));

        verify(repository, never()).save(any());
    }

    @Test
    void transientClassification() {
        assertTrue(DiscoverEmployerJobHandler.isTransient(new org.springframework.web.client.HttpServerErrorException(
                org.springframework.http.HttpStatus.BAD_GATEWAY)));
        assertTrue(DiscoverEmployerJobHandler.isTransient(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "", null, null, null)));
        assertTrue(DiscoverEmployerJobHandler.isTransient(new org.springframework.web.client.ResourceAccessException("timeout")));
        org.junit.jupiter.api.Assertions.assertFalse(DiscoverEmployerJobHandler.isTransient(
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.NOT_FOUND, "", null, null, null)));
        org.junit.jupiter.api.Assertions.assertFalse(DiscoverEmployerJobHandler.isTransient(new IllegalStateException("parse")));
    }

    @Test
    void existingCandidateIsSkipped() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(true);

        handler.handle(message());

        verify(repository, never()).save(any());
        verifyNoInteractions(registry, registrar);
    }

    private EmployerCandidate capture() {
        ArgumentCaptor<EmployerCandidate> captor = ArgumentCaptor.forClass(EmployerCandidate.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
