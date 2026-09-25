package com.roleorienta.worker.discovery.cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.discovery.EmployerCandidateRepository;
import com.roleorienta.worker.discovery.cc.HarvestStore.BoardState;
import com.roleorienta.worker.discovery.cc.HarvestStore.Cursor;
import com.roleorienta.worker.discovery.cc.HarvestStore.PendingBoard;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.outbox.OutboxEvent;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;

/**
 * {@link CcHarvestScheduler} (§55) на заглушках (без сети и БД): аренда, новая коллекция
 * с page 0, продолжение по курсору, бюджет страниц, завершённая коллекция без обхода,
 * освобождение аренды при ошибке, изоляция сбоя сбора от fan-out, fan-out с пропуском
 * известных кандидатов и разбор/дедуп досок страницы.
 */
class CcHarvestSchedulerTest {

    private static final String PATTERN = "*.myworkdayjobs.com";
    private static final String INPUT = CcInput.WORKDAY.code();

    private final CommonCrawlIndexClient index = mock(CommonCrawlIndexClient.class);
    private final HarvestStore store = mock(HarvestStore.class);
    private final EmployerCandidateRepository candidates = mock(EmployerCandidateRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final PostgresLeaderLock leaderLock = mock(PostgresLeaderLock.class);

    @BeforeEach
    void leaderRunsTask() {
        when(leaderLock.runIfLeader(anyLong(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });
    }

    private CcHarvestScheduler scheduler(int pagesPerPass, int maxFanOut) {
        CcHarvestProperties properties = new CcHarvestProperties(List.of(CcInput.WORKDAY), 1, pagesPerPass, maxFanOut, 900);
        return new CcHarvestScheduler(properties, index, store, fanOut(properties));
    }

    private BoardFanOut fanOut(CcHarvestProperties properties) {
        return new BoardFanOut(properties, store, candidates, outbox, leaderLock);
    }

    @Test
    void backlogStopsReadingNewPages() {
        // §73: очередь NEW не меньше бюджета fan-out — новая страница индекса не читается.
        when(store.countNewBoards(INPUT)).thenReturn(20);

        assertEquals(0, scheduler(1, 20).collect());

        verify(store, never()).tryLease(anyString(), anyLong());
        verify(index, never()).latestCollection();
    }

    @Test
    void noLeaseMeansNoNetwork() {
        when(store.tryLease(eq(INPUT), anyLong())).thenReturn(Optional.empty());

        assertEquals(0, scheduler(1, 20).collect());

        verify(index, never()).latestCollection();
        verify(store, never()).releaseLease(anyString());
    }

    @Test
    void newCollectionStartsFromPageZero() {
        when(store.tryLease(eq(INPUT), anyLong())).thenReturn(Optional.of(new Cursor("CC-OLD", 1, 5, 5)));
        when(index.latestCollection()).thenReturn("CC-NEW");
        when(index.pageCount("CC-NEW", PATTERN, 1)).thenReturn(5);
        when(index.urlsOnPage("CC-NEW", PATTERN, 1, 0)).thenReturn(List.of(
                "https://amgen.wd1.myworkdayjobs.com/en-US/Careers/job/x",
                "https://amgen.wd1.myworkdayjobs.com/careers/job/y",
                "https://globalfoundries.wd1.myworkdayjobs.com/robots.txt"));
        when(store.recordPage(any(), any(), any(), any())).thenReturn(1);

        assertEquals(1, scheduler(1, 20).collect());

        InOrder order = inOrder(store);
        order.verify(store).recordPosition(INPUT, new Cursor("CC-NEW", 1, 5, 0));
        order.verify(store).recordPage(eq(INPUT), eq("workday"), eq(new Cursor("CC-NEW", 1, 5, 1)), any());
        order.verify(store).releaseLease(INPUT);
    }

    @Test
    void continuesFromCursorWithinPageBudget() {
        when(store.tryLease(eq(INPUT), anyLong())).thenReturn(Optional.of(new Cursor("CC-1", 1, 5, 2)));
        when(index.latestCollection()).thenReturn("CC-1");
        when(index.urlsOnPage(eq("CC-1"), eq(PATTERN), eq(1), anyInt())).thenReturn(List.of());

        scheduler(2, 20).collect();

        verify(index, never()).pageCount(any(), any(), anyInt());
        verify(index).urlsOnPage("CC-1", PATTERN, 1, 2);
        verify(index).urlsOnPage("CC-1", PATTERN, 1, 3);
        verify(index, never()).urlsOnPage("CC-1", PATTERN, 1, 4);
        verify(store).recordPage(eq(INPUT), any(), eq(new Cursor("CC-1", 1, 5, 4)), any());
    }

    @Test
    void changedPageSizeRestartsCollectionFromZero() {
        when(store.tryLease(eq(INPUT), anyLong())).thenReturn(Optional.of(new Cursor("CC-1", 5, 5, 3)));
        when(index.latestCollection()).thenReturn("CC-1");
        when(index.pageCount("CC-1", PATTERN, 1)).thenReturn(24);
        when(index.urlsOnPage("CC-1", PATTERN, 1, 0)).thenReturn(List.of());

        scheduler(1, 20).collect();

        verify(store).recordPosition(INPUT, new Cursor("CC-1", 1, 24, 0));
        verify(index).urlsOnPage("CC-1", PATTERN, 1, 0);
    }

    @Test
    void finishedCollectionTouchesOnlyCollinfo() {
        when(store.tryLease(eq(INPUT), anyLong())).thenReturn(Optional.of(new Cursor("CC-1", 1, 5, 5)));
        when(index.latestCollection()).thenReturn("CC-1");

        assertEquals(0, scheduler(3, 20).collect());

        verify(index, never()).urlsOnPage(any(), any(), anyInt(), anyInt());
        verify(store).releaseLease(INPUT);
    }

    @Test
    void leaseReleasedOnIndexFailure() {
        when(store.tryLease(eq(INPUT), anyLong())).thenReturn(Optional.of(new Cursor("CC-1", 1, 5, 1)));
        when(index.latestCollection()).thenReturn("CC-1");
        when(index.urlsOnPage(any(), any(), anyInt(), anyInt()))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(HttpServerErrorException.class, () -> scheduler(1, 20).collect());

        verify(store).releaseLease(INPUT);
        verify(store, never()).recordPage(any(), any(), any(), any());
    }

    @Test
    void collectFailureDoesNotBlockFanOut() {
        when(store.tryLease(eq(INPUT), anyLong())).thenReturn(Optional.of(new Cursor(null, 0, 0, 0)));
        when(index.latestCollection()).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        when(store.lockNewBoards(20)).thenReturn(List.of(new PendingBoard(7, "workday", "acme/Careers", "https://acme.wd5.myworkdayjobs.com")));

        scheduler(1, 20).runOnce();

        verify(outbox).save(any(OutboxEvent.class));
        verify(store).mark(7, BoardState.ENQUEUED);
    }

    @Test
    void fanOutEnqueuesNewAndSkipsKnownCandidates() {
        when(store.lockNewBoards(2)).thenReturn(List.of(
                new PendingBoard(1, "workday", "amgen/Careers", "https://amgen.wd1.myworkdayjobs.com"),
                new PendingBoard(2, "workday", "3m/Search", "https://3m.wd1.myworkdayjobs.com")));
        when(candidates.existsByProviderCodeAndSlugIgnoreCase("workday", "amgen/Careers")).thenReturn(true);

        fanOut(new CcHarvestProperties(List.of(CcInput.WORKDAY), 1, 1, 2, 900)).runBatch();

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, times(1)).save(event.capture());
        verify(store).mark(1, BoardState.SKIPPED);
        verify(store).mark(2, BoardState.ENQUEUED);
        assertEquals("DISCOVER_EMPLOYER", event.getValue().getEventType());
    }

    @Test
    void boardsOfParsesAndDedupsIgnoringSiteCase() {
        Collection<HarvestedBoard> boards = CcHarvestScheduler.boardsOf(CcInput.WORKDAY, List.of(
                "https://aig.wd1.myworkdayjobs.com/en-US/aig/job/a",
                "https://aig.wd1.myworkdayjobs.com/AIG/job/b",
                "https://aig.wd1.myworkdayjobs.com/japan",
                "https://globalfoundries.wd1.myworkdayjobs.com/robots.txt",
                "https://www.example.com/x"));

        assertEquals(List.of("aig/aig", "aig/japan"),
                new ArrayList<>(boards).stream().map(HarvestedBoard::slug).toList());
    }

    @Test
    void personioInputsParseAccountFromAnyPageAndDedupAcrossDomains() {
        Collection<HarvestedBoard> boards = CcHarvestScheduler.boardsOf(CcInput.PERSONIO_DE, List.of(
                "https://towa.jobs.personio.de/job/1552584?language=de",
                "https://towa.jobs.personio.de/xml",
                "https://leonine.jobs.personio.de/",
                "https://jobs.personio.de/",
                "https://www.example.com/x"));

        assertEquals(List.of(new HarvestedBoard("towa", "towa", "https://towa.jobs.personio.de"),
                new HarvestedBoard("leonine", "leonine", "https://leonine.jobs.personio.de")),
                new ArrayList<>(boards));
        assertEquals("personio", CcInput.PERSONIO_COM.providerCode());
        assertEquals(List.of(new HarvestedBoard("towa", "towa", "https://towa.jobs.personio.com")),
                new ArrayList<>(CcHarvestScheduler.boardsOf(CcInput.PERSONIO_COM,
                        List.of("https://TOWA.jobs.personio.com/job/1"))));
    }

    @Test
    void everyEnabledInputHasItsOwnCursor() {
        CcHarvestProperties properties = new CcHarvestProperties(
                List.of(CcInput.WORKDAY, CcInput.PERSONIO_DE), 1, 1, 20, 900);
        when(store.tryLease(anyString(), anyLong())).thenReturn(Optional.empty());

        new CcHarvestScheduler(properties, index, store, fanOut(properties)).collect();

        verify(store).tryLease(eq("cc-workday"), anyLong());
        verify(store).tryLease(eq("cc-personio-de"), anyLong());
    }
}
