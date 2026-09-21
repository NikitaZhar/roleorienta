package com.roleorienta.worker.discovery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.discovery.DiscoveryHarvestProperties.SeedEntry;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.outbox.OutboxEvent;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Гарвест-вход обнаружения (§36): бюджет fan-out (A29), дедуп по существующему
 * кандидату, пустой seed. Репозитории и leader-lock — заглушки (без сети и БД);
 * проверяется сам проход {@code harvest()}.
 */
class DiscoveryHarvestSchedulerTest {

    private final EmployerCandidateRepository candidates = mock(EmployerCandidateRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final PostgresLeaderLock leaderLock = mock(PostgresLeaderLock.class);

    private DiscoveryHarvestScheduler scheduler(int maxFanOut, List<SeedEntry> seed) {
        return new DiscoveryHarvestScheduler(
                new DiscoveryHarvestProperties(maxFanOut, seed), candidates, outbox, leaderLock);
    }

    private SeedEntry entry(String slug) {
        return new SeedEntry("greenhouse", slug, "http://stub");
    }

    @Test
    void capsFanOutAtBudget() {
        when(candidates.existsByProviderCodeAndSlug(any(), any())).thenReturn(false);

        scheduler(2, List.of(entry("a"), entry("b"), entry("c"))).harvest();

        verify(outbox, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    void skipsAlreadyKnownCandidates() {
        when(candidates.existsByProviderCodeAndSlug("greenhouse", "a")).thenReturn(true);
        when(candidates.existsByProviderCodeAndSlug("greenhouse", "b")).thenReturn(false);

        scheduler(10, List.of(entry("a"), entry("b"))).harvest();

        verify(outbox, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    void emptySeedEnqueuesNothing() {
        scheduler(10, List.of()).harvest();

        verify(outbox, never()).save(any());
    }
}
