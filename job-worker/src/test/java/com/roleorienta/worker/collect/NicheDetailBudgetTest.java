package com.roleorienta.worker.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.CrawlRun;
import com.roleorienta.core.domain.CrawlRunState;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.core.domain.Provider;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.MarketScope;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.discovery.DiscoveryMarketProperties;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.outbox.OutboxEvent;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import com.roleorienta.worker.scheduling.CrawlRunRepository;
import com.roleorienta.worker.scheduling.CrawlTaskRepository;
import com.roleorienta.worker.scheduling.SourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Ниша и дневной бюджет деталей (§63): в ленту сохраняются все публикации, деталь — только
 * для заголовков ниши, не больше остатка бюджета, новые первыми. Заголовки — реальные из
 * пилота сбора на стенде (§62). Моки, без БД и сети.
 */
class NicheDetailBudgetTest {

    private static final NicheFilterProperties NICHE = new NicheFilterProperties(
            List.of("Java", "Kotlin", "Backend", "Software Engineer", "Software Developer", "DevOps", "Architect"),
            List.of("SAP", "Sales", "Clinical", "Intern"), 3);

    private final SourceRepository sources = mock(SourceRepository.class);
    private final CrawlTaskRepository tasks = mock(CrawlTaskRepository.class);
    private final CrawlRunRepository runs = mock(CrawlRunRepository.class);
    private final JobPostingRepository postings = mock(JobPostingRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final SourceAdapterRegistry adapters = mock(SourceAdapterRegistry.class);
    private final SourceAdapter adapter = mock(SourceAdapter.class);
    private final CrawlRun run = new CrawlRun(null, Instant.EPOCH, CrawlRunState.RUNNING);
    private final Instant now = Instant.parse("2026-09-23T15:00:00Z");

    @BeforeEach
    void setUp() {
        Provider provider = new Provider();
        provider.setCode("workday");
        Source source = new Source();
        source.setId(7L);
        source.setProvider(provider);
        source.setState(SourceState.ACTIVE);
        when(sources.findById(7L)).thenReturn(Optional.of(source));
        when(runs.findById(5L)).thenReturn(Optional.of(run));
        when(tasks.findById(1L)).thenReturn(Optional.of(new CrawlTask(run, CrawlTaskType.DISCOVER_PAGE,
                CrawlTaskState.RUNNING)));
        when(tasks.save(any(CrawlTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adapters.forProviderCode("workday")).thenReturn(adapter);
        when(adapter.listPostings(any(), any(), any(MarketScope.class))).thenReturn(new PostingsPage(List.of(
                posting("/1", "Senior SAP MM Consultant"),
                posting("/2", "Software Developer - Cybersecurity (f/m/d)"),
                posting("/3", "Intern in the Accounting Department"),
                posting("/4", "Senior Java Engineer"),
                posting("/5", "Product Owner / Lead - DevOps Team (w/m/d)"),
                posting("/6", "Backend Engineer (Kotlin)"),
                posting("/7", "Sales Operations Manager (f/m/d)")), null));
    }

    private static DiscoveredPosting posting(String id, String title) {
        return new DiscoveredPosting(id, "http://x" + id, title);
    }

    private void handle() {
        new DiscoverPageJobHandler(sources, runs, tasks, postings, outbox, adapters,
                DiscoveryMarketProperties.ofCountries(List.of("Slovakia", "Austria")), 10, NICHE,
                Clock.fixed(now, ZoneOffset.UTC))
                .handle(new JobMessage("k", "DISCOVER_PAGE", "{\"taskId\":1,\"sourceId\":7,\"crawlRunId\":5}"));
    }

    private List<String> fetchedIds() {
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, org.mockito.Mockito.atLeast(0)).save(events.capture());
        return events.getAllValues().stream()
                .map(e -> e.getPayload().replaceAll(".*\"externalId\":\"([^\"]+)\".*", "$1"))
                .toList();
    }

    @Test
    void titleFilterRealTitles() {
        assertFalse(NICHE.matches("Senior SAP MM Consultant"));
        assertTrue(NICHE.matches("Software Developer - Cybersecurity (f/m/d)"));
        assertFalse(NICHE.matches("Intern in the Accounting Department"));
        assertTrue(NICHE.matches("Product Owner / Lead - DevOps Team (w/m/d)"));
        assertFalse(NICHE.matches("Senior SAP HCM Full-Stack Developer"), "SAP сильнее");
        assertFalse(NICHE.matches("JavaScript Developer"), "Java — целым словом");
        assertTrue(new NicheFilterProperties(List.of(), List.of(), 1).matches("anything"), "ниша не задана");
    }

    @Test
    void allPostingsSavedButDetailOnlyForNicheWithinBudgetNewFirst() {
        when(tasks.countByTypeForSourceSince(eq(CrawlTaskType.FETCH_POSTING), eq(7L), any())).thenReturn(0L);
        when(postings.findDetailedExternalIds(eq(7L), any())).thenReturn(List.of("/2"));

        handle();

        verify(postings, times(7)).upsert(any(), any(), any(), any(), any());
        assertEquals(List.of("/4", "/5", "/6"), fetchedIds(),
                "ниша: /2 /4 /5 /6; бюджет 3; /2 уже с деталью — в конец и не влезает");
    }

    @Test
    void budgetWindowIsTheUtcDayAndExhaustedBudgetFetchesNothing() {
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        when(tasks.countByTypeForSourceSince(eq(CrawlTaskType.FETCH_POSTING), eq(7L), since.capture())).thenReturn(3L);

        handle();

        assertEquals(Instant.parse("2026-09-23T00:00:00Z"), since.getValue());
        verify(postings, times(7)).upsert(any(), any(), any(), any(), any());
        verify(outbox, never()).save(any());
    }
}
