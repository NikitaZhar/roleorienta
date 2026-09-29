package com.roleorienta.worker.collect;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.adapter.PostingCheck;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.snapshot.SnapshotStore;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Запрос текста публикаций при чтении: только без сохранённого текста и в пределах потолка;
 * проверка публикаций, пропавших из списка с фильтром по стране.
 */
class ReadSourceHandlerTests {

    private static final String BOARD = "board";

    private final SourceRepository sources = mock(SourceRepository.class);
    private final PostingRecorder recorder = mock(PostingRecorder.class);
    private final SourceAdapter adapter = mock(SourceAdapter.class);

    /**
     * {@code a} — текст уже сохранён, {@code b} — запрошен, {@code c} — за потолком (1 запрос).
     */
    @Test
    void requestsContentOnlyForPostingsWithoutStoredTextWithinLimit() {
        Source source = new Source("workday", BOARD);
        when(adapter.read(BOARD, null)).thenReturn(SourceReadResult.Read.full(
                List.of(posting("a", null), posting("b", null), posting("c", null)), List.of()));
        when(recorder.externalIdsWithContent(source)).thenReturn(Set.of("a"));
        when(adapter.content(BOARD, "b")).thenReturn("text b");

        handler(source).handle(new TaskRecord(1, ReadSourceHandler.TYPE, ReadSourceHandler.payload(1), 0));

        verify(adapter, never()).content(BOARD, "a");
        verify(adapter, never()).content(BOARD, "c");
        verify(recorder).record(any(CrawlRun.class),
                eq(List.of(posting("a", null), posting("b", "text b"), posting("c", null))), eq(Set.of()));
    }

    /**
     * Источник со страной: пропавшие из списка {@code b} (есть у источника), {@code c} (нет) и
     * {@code d} (за потолком проверок, 2) — {@code b} остаётся с новыми сведениями, {@code d} не
     * засчитывается отсутствующей.
     */
    @Test
    void checksPostingsMissingFromCountryFilteredList() {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(1L);
        when(source.getProvider()).thenReturn("workday");
        when(source.getBoard()).thenReturn(BOARD);
        when(source.getCountry()).thenReturn("SK");
        when(adapter.read(BOARD, "SK")).thenReturn(SourceReadResult.Read.full(List.of(posting("a", "t")), List.of()));
        when(recorder.openExternalIds(source)).thenReturn(List.of("a", "b", "c", "d"));
        when(recorder.externalIdsWithContent(source)).thenReturn(Set.of());
        when(adapter.check(BOARD, "b")).thenReturn(new PostingCheck.Present(posting("b", "moved")));
        when(adapter.check(BOARD, "c")).thenReturn(new PostingCheck.Absent());

        handler(source).handle(new TaskRecord(1, ReadSourceHandler.TYPE, ReadSourceHandler.payload(1), 0));

        verify(adapter, never()).check(BOARD, "a");
        verify(adapter, never()).check(BOARD, "d");
        verify(recorder).record(any(CrawlRun.class), eq(List.of(posting("a", "t"), posting("b", "moved"))),
                eq(Set.of("d")));
    }

    @SuppressWarnings("unchecked")
    private ReadSourceHandler handler(Source source) {
        ObjectProvider<SourceAdapter> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream()).thenReturn(Stream.of(adapter));
        when(adapter.provider()).thenReturn("workday");
        when(sources.findById(1L)).thenReturn(Optional.of(source));
        CrawlRun run = CrawlRun.start(source, 1, Instant.EPOCH);
        when(recorder.startRun(source, 1)).thenReturn(run);
        return new ReadSourceHandler(sources, adapters, recorder, new CollectProperties(1, 2),
                mock(SnapshotStore.class));
    }

    private static FetchedPosting posting(String externalId, String content) {
        return new FetchedPosting(externalId, "Title", "https://example.com/" + externalId, null, content);
    }
}
