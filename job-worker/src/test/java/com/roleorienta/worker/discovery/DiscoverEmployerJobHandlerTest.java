package com.roleorienta.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.roleorienta.worker.jobs.JobMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Логика гейта обнаружения (§33): проверка ленты → уверенность/причина, дедуп,
 * устойчивость к ошибке чтения. Реестр адаптеров, адаптер и репозиторий —
 * заглушки (без сети и БД).
 */
class DiscoverEmployerJobHandlerTest {

    private final SourceAdapterRegistry registry = mock(SourceAdapterRegistry.class);
    private final SourceAdapter adapter = mock(SourceAdapter.class);
    private final EmployerCandidateRepository repository = mock(EmployerCandidateRepository.class);
    private final DiscoverEmployerJobHandler handler = new DiscoverEmployerJobHandler(registry, repository);

    private static final String PAYLOAD =
            "{\"providerCode\":\"greenhouse\",\"slug\":\"acme\",\"baseUrl\":\"http://stub\"}";

    private JobMessage message() {
        return new JobMessage("key-1", "DISCOVER_EMPLOYER", PAYLOAD);
    }

    @Test
    void nonEmptyFeedYieldsHighConfidenceCandidate() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(
                List.of(new DiscoveredPosting("1", "http://stub/1", "Dev")), null));

        handler.handle(message());

        EmployerCandidate saved = capture();
        assertEquals(EmployerCandidateState.PENDING, saved.getState());
        assertEquals(DiscoveryConfidence.HIGH, saved.getConfidence());
        assertEquals(1, saved.getPostingCount());
        assertEquals("greenhouse", saved.getProviderCode());
        assertEquals("acme", saved.getSlug());
        assertEquals("http://stub", saved.getBaseUrl());
    }

    @Test
    void emptyFeedYieldsLowConfidence() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenReturn(new PostingsPage(List.of(), null));

        handler.handle(message());

        EmployerCandidate saved = capture();
        assertEquals(DiscoveryConfidence.LOW, saved.getConfidence());
        assertEquals(0, saved.getPostingCount());
        assertEquals(EmployerCandidateState.PENDING, saved.getState());
    }

    @Test
    void unreadableFeedYieldsNoneConfidenceButStillQueued() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(false);
        when(registry.forProviderCode("greenhouse")).thenReturn(adapter);
        when(adapter.listPostings(any(), any())).thenThrow(new RuntimeException("boom"));

        handler.handle(message());

        EmployerCandidate saved = capture();
        assertEquals(DiscoveryConfidence.NONE, saved.getConfidence());
        assertTrue(saved.getReason().contains("boom"), "причина содержит текст ошибки");
        assertEquals(EmployerCandidateState.PENDING, saved.getState());
    }

    @Test
    void existingCandidateIsSkipped() {
        when(repository.existsByProviderCodeAndSlug("greenhouse", "acme")).thenReturn(true);

        handler.handle(message());

        verify(repository, never()).save(any());
        verifyNoInteractions(registry);
    }

    private EmployerCandidate capture() {
        ArgumentCaptor<EmployerCandidate> captor = ArgumentCaptor.forClass(EmployerCandidate.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
