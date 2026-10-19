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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Запрос детали публикаций (текст и места) при чтении: только без сохранённого текста и в пределах потолка;
 * проверка публикаций, пропавших из списка с фильтром по стране; название работодателя источника без компании; дата
 * размещения из детали.
 */
class ReadSourceHandlerTests {

    private static final String BOARD = "board";

    private final SourceRepository sources = mock(SourceRepository.class);
    private final PostingRecorder recorder = mock(PostingRecorder.class);
    private final SourceAdapter adapter = mock(SourceAdapter.class);

    /**
     * {@code a} — текст уже сохранён, {@code b} — деталь запрошена (её место заменяет место списка),
     * {@code c} — за потолком (1 запрос).
     */
    @Test
    void requestsDetailOnlyForPostingsWithoutStoredTextWithinLimit() {
        Source source = new Source("workday", BOARD);
        when(adapter.read(BOARD, null)).thenReturn(SourceReadResult.Read.full(
                List.of(posting("a", null), posting("b", null), posting("c", null)), List.of()));
        when(recorder.externalIdsWithContent(source)).thenReturn(Set.of("a"));
        when(adapter.detail(BOARD, "b")).thenReturn(new FetchedPosting("b", "Title", "https://example.com/b",
                "IND.Pune", "text b"));

        handler(source).handle(new TaskRecord(1, ReadSourceHandler.TYPE, ReadSourceHandler.payload(1), 0));

        verify(adapter, never()).detail(BOARD, "a");
        verify(adapter, never()).detail(BOARD, "c");
        verify(recorder).record(any(CrawlRun.class), eq(List.of(posting("a", null),
                new FetchedPosting("b", "Title", "https://example.com/b", "IND.Pune", "text b"),
                posting("c", null))), eq(Set.of()), eq(Set.of()), eq(Map.of()));
    }

    /**
     * Дата размещения из детали (§128, потолок 1): {@code a} — дата есть в списке, {@code b} — у вакансии уже есть,
     * {@code c} — спрошена у детали, {@code d} — за потолком.
     */
    @Test
    void asksPostedDateOnlyWhereNeitherListNorVacancyHasIt() {
        Source source = new Source("workday", BOARD);
        Instant listed = Instant.parse("2026-10-01T00:00:00Z");
        Instant detailed = Instant.parse("2026-09-01T00:00:00Z");
        List<FetchedPosting> postings = List.of(posting("a", "text"), posting("b", "text"), posting("c", "text"),
                posting("d", "text"));
        when(adapter.read(BOARD, null)).thenReturn(SourceReadResult.Read.full(postings, List.of())
                .withPostedAt(Map.of("a", listed)));
        when(recorder.externalIdsWithPostedDate(source)).thenReturn(Set.of("b"));
        when(adapter.postedDate(BOARD, "c")).thenReturn(Optional.of(detailed));

        handler(source).handle(new TaskRecord(1, ReadSourceHandler.TYPE, ReadSourceHandler.payload(1), 0));

        verify(adapter, never()).postedDate(BOARD, "a");
        verify(adapter, never()).postedDate(BOARD, "b");
        verify(adapter, never()).postedDate(BOARD, "d");
        verify(recorder).record(any(CrawlRun.class), eq(postings), eq(Set.of()), eq(Set.of()),
                eq(Map.of("a", listed, "c", detailed)));
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
                eq(Set.of("d")), eq(Set.of()), eq(Map.of()));
    }

    /**
     * Окно по кругу (аудит §121), источник со страной: {@code b} есть в списке, но не прочитана в это чтение — не
     * проверяется (проверка по умолчанию ответила бы «нет» и закрыла бы её) и передаётся записи как непрочитанная;
     * {@code c} в списке нет — проверяется как обычно.
     */
    @Test
    void doesNotCheckPostingsListedButNotReadNow() {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(1L);
        when(source.getProvider()).thenReturn("workday");
        when(source.getBoard()).thenReturn(BOARD);
        when(source.getCountry()).thenReturn("SK");
        when(adapter.read(BOARD, "SK")).thenReturn(new SourceReadResult.Read(List.of(posting("a", "t")), null,
                List.of(), Set.of("b")));
        when(recorder.openExternalIds(source)).thenReturn(List.of("a", "b", "c"));
        when(recorder.externalIdsWithContent(source)).thenReturn(Set.of());
        when(adapter.check(BOARD, "c")).thenReturn(new PostingCheck.Absent());

        handler(source).handle(new TaskRecord(1, ReadSourceHandler.TYPE, ReadSourceHandler.payload(1), 0));

        verify(adapter, never()).check(BOARD, "b");
        verify(adapter).check(BOARD, "c");
        verify(recorder).record(any(CrawlRun.class), eq(List.of(posting("a", "t"))), eq(Set.of()), eq(Set.of("b")),
                eq(Map.of()));
    }

    /**
     * Источник без компании (§123): у {@code a} название работодателя пустое, у {@code b} есть — записывается
     * название {@code b}, у {@code c} не спрашивается.
     */
    @Test
    void namesEmployerFromFirstPostingThatHasName() {
        Source source = new Source("workday", BOARD);
        when(adapter.read(BOARD, null)).thenReturn(SourceReadResult.Read.full(
                List.of(posting("a", "t"), posting("b", "t"), posting("c", "t")), List.of()));
        when(sources.needsEmployerName(any())).thenReturn(true);
        when(adapter.employerName(BOARD, "a")).thenReturn(Optional.empty());
        when(adapter.employerName(BOARD, "b")).thenReturn(Optional.of("Acme s.r.o."));

        handler(source).handle(new TaskRecord(1, ReadSourceHandler.TYPE, ReadSourceHandler.payload(1), 0));

        verify(sources).nameEmployer(any(), eq("Acme s.r.o."));
        verify(adapter, never()).employerName(BOARD, "c");
    }

    /**
     * Ни у одной из первых 5 публикаций названия нет — шестая не спрашивается, ничего не записывается (§123).
     */
    @Test
    void asksEmployerNameOfAtMostFivePostings() {
        Source source = new Source("workday", BOARD);
        when(adapter.read(BOARD, null)).thenReturn(SourceReadResult.Read.full(List.of(posting("a", "t"),
                posting("b", "t"), posting("c", "t"), posting("d", "t"), posting("e", "t"), posting("f", "t")),
                List.of()));
        when(sources.needsEmployerName(any())).thenReturn(true);

        handler(source).handle(new TaskRecord(1, ReadSourceHandler.TYPE, ReadSourceHandler.payload(1), 0));

        verify(adapter).employerName(BOARD, "e");
        verify(adapter, never()).employerName(BOARD, "f");
        verify(sources, never()).nameEmployer(any(), any());
    }

    @SuppressWarnings("unchecked")
    private ReadSourceHandler handler(Source source) {
        ObjectProvider<SourceAdapter> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream()).thenReturn(Stream.of(adapter));
        when(adapter.provider()).thenReturn("workday");
        when(sources.findById(1L)).thenReturn(Optional.of(source));
        CrawlRun run = CrawlRun.start(source, 1, Instant.EPOCH);
        when(recorder.startRun(source, 1)).thenReturn(run);
        return new ReadSourceHandler(sources, adapters, recorder, new CollectProperties(1, 2, Duration.ofHours(23), List.of()),
                mock(SnapshotStore.class));
    }

    private static FetchedPosting posting(String externalId, String content) {
        return new FetchedPosting(externalId, "Title", "https://example.com/" + externalId, null, content);
    }
}
