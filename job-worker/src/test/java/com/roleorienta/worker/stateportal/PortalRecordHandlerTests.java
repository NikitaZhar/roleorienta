package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.adapter.stateportal.StatePortalProperties;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Страницы объявлений в задании {@code PORTAL_RECORD} (§168, §203; аудит §182) без БД и сети: запрашиваются у
 * публикаций, чья вакансия ещё без даты и чей id даты допускает (своя вакансия портала, объявление profesia.sk), или у
 * своих вакансий портала без кода профессии; не больше {@code offer-pages-per-record} за задание, по кругу с места,
 * зависящего от номера задания; даты уходят в запись публикаций, коды профессий — после неё.
 */
class PortalRecordHandlerTests {

    private static final long SOURCE_ID = 7;
    private static final String BOARD = "12345678";
    private static final long CYCLE = 1;
    private static final String UUID_A = "0a0a0a0a-1111-4111-8111-000000000001";
    private static final String UUID_B = "0a0a0a0a-1111-4111-8111-000000000002";
    private static final String UUID_DATED = "0a0a0a0a-1111-4111-8111-000000000003";
    private static final String PROFESIA = "profesia:1";
    private static final String OTHER_SITE = "https://www.worki.sk/ponuka/1";
    private static final Instant POSTED = Instant.parse("2026-10-14T00:00:00Z");
    private static final String FREIGHT_HANDLERS = "9333";
    /** Публикации источника: две без даты, одна с датой, объявление profesia.sk без даты, объявление другой площадки. */
    private static final List<FetchedPosting> POSTINGS = List.of(posting(UUID_A), posting(UUID_DATED),
            posting(UUID_B), posting(PROFESIA), posting(OTHER_SITE));

    private final SourceRepository sources = mock(SourceRepository.class);
    private final PortalListRepository repository = mock(PortalListRepository.class);
    private final PostingRecorder recorder = mock(PostingRecorder.class);
    private final StatePortalAdapter adapter = mock(StatePortalAdapter.class);
    private final Source source = mock(Source.class);
    private final CrawlRun run = mock(CrawlRun.class);

    @BeforeEach
    void setUp() {
        when(source.getId()).thenReturn(SOURCE_ID);
        when(source.getBoard()).thenReturn(BOARD);
        when(sources.findById(SOURCE_ID)).thenReturn(Optional.of(source));
        when(repository.postingsOf(BOARD, CYCLE)).thenReturn(POSTINGS);
        when(recorder.externalIdsWithPostedDate(source)).thenReturn(Set.of(UUID_DATED));
        when(repository.externalIdsWithOccupation(SOURCE_ID)).thenReturn(Set.of(UUID_DATED));
        when(recorder.startRun(eq(source), anyLong())).thenReturn(run);
        when(adapter.offerPage(anyString(), anyString())).thenReturn(Optional.empty());
        when(adapter.offerPage(eq(UUID_B), anyString()))
                .thenReturn(Optional.of(new StatePortalAdapter.OfferPage(null, FREIGHT_HANDLERS)));
        when(adapter.offerPage(eq(PROFESIA), anyString()))
                .thenReturn(Optional.of(new StatePortalAdapter.OfferPage(POSTED, null)));
    }

    /**
     * Без даты или без кода — UUID_A, UUID_B, profesia:1 (у вакансии с датой код уже есть); бюджет 2, задание 1 — с места
     * 1: UUID_B (код профессии есть, даты нет) и profesia:1 (дата есть). UUID_A, вакансия с датой и кодом и объявление
     * другой площадки не запрашиваются; дата — в запись публикаций, код — после неё.
     */
    @Test
    void requestsOfferPagesWithinBudgetFromSeedAndPassesFactsOn() {
        TaskOutcome outcome = handler(2).handle(task(1));

        assertThat(outcome).isInstanceOf(TaskOutcome.Done.class);
        verify(adapter).offerPage(eq(UUID_B), anyString());
        verify(adapter).offerPage(eq(PROFESIA), anyString());
        verify(adapter, never()).offerPage(eq(UUID_A), anyString());
        verify(adapter, never()).offerPage(eq(UUID_DATED), anyString());
        verify(adapter, never()).offerPage(eq(OTHER_SITE), anyString());
        verify(recorder).record(run, POSTINGS, Set.of(), Set.of(), Map.of(PROFESIA, POSTED));
        verify(repository).recordOccupations(SOURCE_ID, Map.of(UUID_B, FREIGHT_HANDLERS));
    }

    /** Вакансия портала с датой, но без кода профессии (накопленная до §203) — её страница запрашивается за кодом. */
    @Test
    void datedPortalOfferWithoutOccupationIsRequested() {
        when(repository.externalIdsWithOccupation(SOURCE_ID)).thenReturn(Set.of());

        handler(POSTINGS.size()).handle(task(0));

        verify(adapter).offerPage(eq(UUID_DATED), anyString());
        verify(adapter, never()).offerPage(eq(OTHER_SITE), anyString());
    }

    /** Бюджет 0 — страницы объявлений не запрашиваются, запись без дат и кодов. */
    @Test
    void zeroBudgetRequestsNoOfferPages() {
        handler(0).handle(task(1));

        verify(adapter, never()).offerPage(anyString(), anyString());
        verify(recorder).record(run, POSTINGS, Set.of(), Set.of(), Map.of());
        verify(repository).recordOccupations(SOURCE_ID, Map.of());
    }

    private PortalRecordHandler handler(int offerPagesPerRecord) {
        StatePortalProperties properties = new StatePortalProperties("http://portal.test", 50,
                new StatePortalProperties.Task(20, Duration.ofMinutes(5)), Duration.ofDays(7), offerPagesPerRecord);
        return new PortalRecordHandler(sources, repository, recorder, adapter, properties);
    }

    private static TaskRecord task(long id) {
        return new TaskRecord(id, PortalRecordHandler.TYPE, PortalRecordHandler.payload(SOURCE_ID, CYCLE, null), 0);
    }

    private static FetchedPosting posting(String externalId) {
        return new FetchedPosting(externalId, "Pozícia", "https://www.sluzbyzamestnanosti.gov.sk/" + externalId,
                null, null);
    }
}
