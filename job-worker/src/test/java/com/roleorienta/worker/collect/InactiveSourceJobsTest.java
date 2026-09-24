package com.roleorienta.worker.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.CrawlRun;
import com.roleorienta.core.domain.CrawlRunState;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import com.roleorienta.worker.scheduling.CrawlRunRepository;
import com.roleorienta.worker.scheduling.CrawlTaskRepository;
import com.roleorienta.worker.scheduling.SourceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Задания сбора по источнику, который поставлен на паузу или отключён <b>после</b>
 * постановки заданий (§60): {@code DISCOVER_PAGE} и {@code FETCH_POSTING} снимаются без
 * обращения к источнику (адаптер не вызывается), задание — {@code FAILED}. Без БД и сети.
 */
class InactiveSourceJobsTest {

    private final SourceRepository sources = mock(SourceRepository.class);
    private final CrawlTaskRepository tasks = mock(CrawlTaskRepository.class);
    private final CrawlRunRepository runs = mock(CrawlRunRepository.class);
    private final JobPostingRepository postings = mock(JobPostingRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final SourceAdapterRegistry adapters = mock(SourceAdapterRegistry.class);
    private final PostingEnricher enricher = mock(PostingEnricher.class);

    private final CrawlRun run = new CrawlRun(null, java.time.Instant.EPOCH, CrawlRunState.RUNNING);
    private final CrawlTask task = new CrawlTask(run, com.roleorienta.core.domain.CrawlTaskType.FETCH_POSTING,
            CrawlTaskState.RUNNING);

    private void pausedSource() {
        Source source = new Source();
        source.setState(SourceState.PAUSED);
        when(sources.findById(9L)).thenReturn(Optional.of(source));
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
    }

    @Test
    void fetchPostingForPausedSourceIsDroppedWithoutRequest() {
        pausedSource();

        new FetchPostingJobHandler(sources, tasks, postings, adapters, enricher).handle(new JobMessage("k",
                "FETCH_POSTING", "{\"taskId\":1,\"sourceId\":9,\"externalId\":\"/job/x\"}"));

        verifyNoInteractions(adapters, enricher);
        verify(postings, never()).save(any());
        assertEquals(CrawlTaskState.FAILED, task.getState());
    }

    @Test
    void discoverPageForPausedSourceIsDroppedWithoutRequest() {
        pausedSource();
        when(runs.findById(5L)).thenReturn(Optional.of(run));

        new DiscoverPageJobHandler(new CrawlBookkeeping(sources, runs, tasks), adapters,
                com.roleorienta.worker.discovery.DiscoveryMarketProperties.ofCountries(java.util.List.of("Austria")), 10,
                new PostingIntake(postings, tasks, outbox, new NicheFilterProperties(java.util.List.of("Java", "Backend", "Software Engineer"), java.util.List.of("SAP", "Intern"), 30), java.time.Clock.systemUTC())).handle(new JobMessage("k",
                "DISCOVER_PAGE", "{\"taskId\":1,\"sourceId\":9,\"crawlRunId\":5}"));

        verifyNoInteractions(adapters, outbox);
        assertEquals(CrawlRunState.FAILED, run.getState());
        assertEquals(CrawlTaskState.FAILED, task.getState());
    }
}
