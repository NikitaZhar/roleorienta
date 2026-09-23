package com.roleorienta.worker.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.roleorienta.worker.outbox.OutboxEventRepository;
import com.roleorienta.worker.scheduling.CrawlRunRepository;
import com.roleorienta.worker.scheduling.CrawlTaskRepository;
import com.roleorienta.worker.scheduling.SourceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code DISCOVER_PAGE} с областью рынка и пагинацией (§62), на моках (без БД и сети):
 * адаптер получает область рынка, страницы читаются по курсору до конца, но не больше
 * потолка; каждая публикация сохраняется и получает {@code FETCH_POSTING}.
 */
class DiscoverPageMarketCollectionTest {

    private final SourceRepository sources = mock(SourceRepository.class);
    private final CrawlTaskRepository tasks = mock(CrawlTaskRepository.class);
    private final CrawlRunRepository runs = mock(CrawlRunRepository.class);
    private final JobPostingRepository postings = mock(JobPostingRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final SourceAdapterRegistry adapters = mock(SourceAdapterRegistry.class);
    private final SourceAdapter adapter = mock(SourceAdapter.class);

    private final CrawlRun run = new CrawlRun(null, java.time.Instant.EPOCH, CrawlRunState.RUNNING);
    private final CrawlTask task = new CrawlTask(run, CrawlTaskType.DISCOVER_PAGE, CrawlTaskState.RUNNING);

    @BeforeEach
    void setUp() {
        Provider provider = new Provider();
        provider.setCode("workday");
        Source source = new Source();
        source.setProvider(provider);
        source.setState(SourceState.ACTIVE);
        when(sources.findById(9L)).thenReturn(Optional.of(source));
        when(runs.findById(5L)).thenReturn(Optional.of(run));
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
        when(tasks.save(any(CrawlTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adapters.forProviderCode("workday")).thenReturn(adapter);
    }

    private DiscoverPageJobHandler handler(int maxPages) {
        return new DiscoverPageJobHandler(sources, runs, tasks, postings, outbox, adapters,
                DiscoveryMarketProperties.ofCountries(List.of("Slovakia", "Austria")), maxPages,
                new NicheFilterProperties(java.util.List.of("Java", "Backend", "Software Engineer"), java.util.List.of("SAP", "Intern"), 30), java.time.Clock.systemUTC());
    }

    private static PostingsPage page(String cursor, String... ids) {
        return new PostingsPage(java.util.Arrays.stream(ids)
                .map(id -> new DiscoveredPosting(id, "http://x" + id, "Java " + id)).toList(), cursor);
    }

    private void handle(DiscoverPageJobHandler handler) {
        handler.handle(new JobMessage("k", "DISCOVER_PAGE", "{\"taskId\":1,\"sourceId\":9,\"crawlRunId\":5}"));
    }

    @Test
    void readsAllPagesInMarketScope() {
        when(adapter.listPostings(any(), eq(null), any(MarketScope.class))).thenReturn(page("20|c", "/a", "/b"));
        when(adapter.listPostings(any(), eq("20|c"), any(MarketScope.class))).thenReturn(page(null, "/c"));

        handle(handler(10));

        ArgumentCaptor<MarketScope> scope = ArgumentCaptor.forClass(MarketScope.class);
        verify(adapter, times(2)).listPostings(any(), any(), scope.capture());
        assertTrue(scope.getValue().restricted(), "сбор идёт в области рынка");
        assertTrue(scope.getValue().isMarketCountry().test("Slovakia"));
        verify(postings, times(3)).upsert(any(), any(), any(), any(), any());
        verify(outbox, times(3)).save(any());
        assertEquals(CrawlTaskState.SUCCEEDED, task.getState());
    }

    @Test
    void stopsAtPageBudget() {
        when(adapter.listPostings(any(), any(), any(MarketScope.class))).thenReturn(page("more", "/a"));

        handle(handler(3));

        verify(adapter, times(3)).listPostings(any(), any(), any(MarketScope.class));
        verify(postings, times(3)).upsert(any(), any(), any(), any(), any());
    }
}
