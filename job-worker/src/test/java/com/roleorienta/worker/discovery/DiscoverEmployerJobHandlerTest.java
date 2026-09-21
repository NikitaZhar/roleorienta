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
 * CONFIRMED; LOW/NONE → очередь на подтверждение (PENDING); дедуп. Реестр адаптеров,
 * адаптер, регистратор источника и репозиторий — заглушки (без сети и БД).
 */
class DiscoverEmployerJobHandlerTest {

    private final SourceAdapterRegistry registry = mock(SourceAdapterRegistry.class);
    private final SourceAdapter adapter = mock(SourceAdapter.class);
    private final EmployerCandidateRepository repository = mock(EmployerCandidateRepository.class);
    private final EmployerSourceRegistrar registrar = mock(EmployerSourceRegistrar.class);
    private final DiscoverEmployerJobHandler handler =
            new DiscoverEmployerJobHandler(registry, repository, registrar);

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
