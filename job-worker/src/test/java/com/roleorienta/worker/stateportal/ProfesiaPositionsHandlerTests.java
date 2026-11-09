package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.adapter.stateportal.ProfesiaPosition;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Задание {@code PROFESIA_POSITIONS} (§267, §268, §271) без БД и сети: позиции со страниц объявлений записываются по
 * источнику (и пустые — страница прочитана); ссылка на вакансию у работодателя — выясненная, и «ссылки нет»; не
 * выяснена — страница откладывается; страница без объявления (капча) позиций не записывает и откладывается, три
 * подряд — задание повторяется, их не откладывая; profesia.sk временно недоступна — прочитанное записано, задание
 * повторяется.
 */
class ProfesiaPositionsHandlerTests {

    private static final String EMPLOYER_JOB =
            "https://accenture.wd103.myworkdayjobs.com/sk-SK/AccentureCareers/job/Bratislava/Business-Analyst_R00361539";
    private static final List<ProfesiaPosition> SELLER = List.of(
            new ProfesiaPosition("predavac-predavacka", "Predavač/predavačka"));

    private final PortalListRepository repository = mock(PortalListRepository.class);
    private final ProfesiaPages pages = mock(ProfesiaPages.class);
    private final ProfesiaPositionsHandler handler = new ProfesiaPositionsHandler(repository, pages);

    @Test
    void positionsAndLinksAreRecordedBySource() {
        when(repository.uncheckedProfesiaAds(anyInt())).thenReturn(List.of(ad(7, 1), ad(7, 2), ad(9, 3)));
        when(pages.positions(url(1))).thenReturn(facts(SELLER, new StatePortalAdapter.EmployerLink(EMPLOYER_JOB)));
        when(pages.positions(url(2))).thenReturn(facts(List.of(), new StatePortalAdapter.EmployerLink(null)));
        when(pages.positions(url(3))).thenReturn(facts(SELLER, null));

        TaskOutcome outcome = handler.handle(task());

        assertThat(outcome).isInstanceOf(TaskOutcome.Done.class);
        verify(repository).recordProfesiaPositions(7, Map.of("profesia:1", SELLER, "profesia:2", List.of()));
        verify(repository).recordProfesiaPositions(9, Map.of("profesia:3", SELLER));
        Map<String, String> links = new HashMap<>();
        links.put("profesia:1", EMPLOYER_JOB);
        links.put("profesia:2", null);
        verify(repository).recordEmployerLinks(7, links);
        verify(repository).deferProfesiaPages(9, List.of("profesia:3"));
    }

    @Test
    void pageWithoutOfferRecordsNothingAndIsDeferred() {
        when(repository.uncheckedProfesiaAds(anyInt())).thenReturn(List.of(ad(7, 1), ad(7, 2)));
        when(pages.positions(url(1))).thenReturn(unparsed());
        when(pages.positions(url(2))).thenReturn(facts(SELLER, new StatePortalAdapter.EmployerLink(null)));

        TaskOutcome outcome = handler.handle(task());

        assertThat(outcome).isInstanceOf(TaskOutcome.Done.class);
        verify(repository).recordProfesiaPositions(7, Map.of("profesia:2", SELLER));
        verify(repository).deferProfesiaPages(7, List.of("profesia:1"));
    }

    @Test
    void captchaInARowStopsWithoutDeferring() {
        when(repository.uncheckedProfesiaAds(anyInt())).thenReturn(List.of(ad(7, 1), ad(7, 2), ad(7, 3), ad(7, 4),
                ad(7, 5)));
        when(pages.positions(url(1))).thenReturn(facts(SELLER, new StatePortalAdapter.EmployerLink(null)));
        when(pages.positions(url(2))).thenReturn(unparsed());
        when(pages.positions(url(3))).thenReturn(unparsed());
        when(pages.positions(url(4))).thenReturn(unparsed());

        TaskOutcome outcome = handler.handle(task());

        assertThat(outcome).isInstanceOf(TaskOutcome.Retry.class);
        verify(repository).recordProfesiaPositions(7, Map.of("profesia:1", SELLER));
        verify(repository, never()).deferProfesiaPages(anyLong(), any());
        verify(pages, never()).positions(url(5));
    }

    @Test
    void unavailableProfesiaKeepsWhatWasReadAndRetries() {
        when(repository.uncheckedProfesiaAds(anyInt())).thenReturn(List.of(ad(7, 1), ad(7, 2)));
        when(pages.positions(url(1))).thenReturn(facts(SELLER, new StatePortalAdapter.EmployerLink(null)));
        when(pages.positions(url(2))).thenReturn(Optional.empty());

        TaskOutcome outcome = handler.handle(task());

        assertThat(outcome).isInstanceOf(TaskOutcome.Retry.class);
        verify(repository).recordProfesiaPositions(7, Map.of("profesia:1", SELLER));
    }

    private static PortalListRepository.UncheckedAd ad(long sourceId, int number) {
        return new PortalListRepository.UncheckedAd(sourceId, "profesia:" + number, url(number));
    }

    private static String url(int number) {
        return "https://www.profesia.sk/O" + number;
    }

    private static Optional<ProfesiaPages.AdFacts> facts(List<ProfesiaPosition> positions,
            StatePortalAdapter.EmployerLink link) {
        return Optional.of(new ProfesiaPages.AdFacts(positions, link, true));
    }

    private static Optional<ProfesiaPages.AdFacts> unparsed() {
        return Optional.of(new ProfesiaPages.AdFacts(List.of(), null, false));
    }

    private static TaskRecord task() {
        return new TaskRecord(0, ProfesiaPositionsHandler.TYPE, "{}", 0);
    }
}
