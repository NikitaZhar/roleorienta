package com.roleorienta.worker.collect;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Запрос текста публикаций при чтении: только без сохранённого текста и в пределах потолка.
 */
class ReadSourceHandlerTests {

    private static final String BOARD = "board";

    private final SourceRepository sources = mock(SourceRepository.class);
    private final PostingRecorder recorder = mock(PostingRecorder.class);
    private final SourceAdapter adapter = mock(SourceAdapter.class);
    private final Source source = new Source("workday", BOARD);

    /**
     * {@code a} — текст уже сохранён, {@code b} — запрошен, {@code c} — за потолком (1 запрос).
     */
    @Test
    @SuppressWarnings("unchecked")
    void requestsContentOnlyForPostingsWithoutStoredTextWithinLimit() {
        ObjectProvider<SourceAdapter> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream()).thenReturn(Stream.of(adapter));
        when(adapter.provider()).thenReturn("workday");
        when(sources.findById(1L)).thenReturn(Optional.of(source));
        when(adapter.read(BOARD)).thenReturn(new SourceReadResult.Read(
                List.of(posting("a", null), posting("b", null), posting("c", null)), true));
        when(recorder.externalIdsWithContent(source)).thenReturn(Set.of("a"));
        when(adapter.content(BOARD, "b")).thenReturn("text b");
        ReadSourceHandler handler = new ReadSourceHandler(sources, adapters, recorder, new CollectProperties(1));

        handler.handle(new TaskRecord(1, ReadSourceHandler.TYPE, ReadSourceHandler.payload(1), 0));

        verify(adapter, never()).content(BOARD, "a");
        verify(adapter, never()).content(BOARD, "c");
        verify(recorder).record(source,
                List.of(posting("a", null), posting("b", "text b"), posting("c", null)), true);
    }

    private static FetchedPosting posting(String externalId, String content) {
        return new FetchedPosting(externalId, "Title", "https://example.com/" + externalId, null, content);
    }
}
