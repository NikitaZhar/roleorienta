package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.adapter.stateportal.ProfesiaPosition;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Задание {@code PROFESIA_POSITIONS} (§267, §268) без БД и сети: позиции со страниц объявлений без позиций
 * записываются по источнику (и пустые — страница прочитана), ссылка на вакансию у работодателя — если есть; profesia.sk временно недоступна — прочитанное записано, задание
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
    void positionsAreRecordedBySource() {
        when(repository.uncheckedProfesiaAds(anyInt())).thenReturn(List.of(
                new PortalListRepository.UncheckedAd(7, "profesia:1", "https://www.profesia.sk/O1"),
                new PortalListRepository.UncheckedAd(7, "profesia:2", "https://www.profesia.sk/O2"),
                new PortalListRepository.UncheckedAd(9, "profesia:3", "https://www.profesia.sk/O3")));
        when(pages.positions("https://www.profesia.sk/O1")).thenReturn(facts(SELLER, EMPLOYER_JOB));
        when(pages.positions("https://www.profesia.sk/O2")).thenReturn(facts(List.of(), null));
        when(pages.positions("https://www.profesia.sk/O3")).thenReturn(facts(SELLER, null));

        TaskOutcome outcome = handler.handle(task());

        assertThat(outcome).isInstanceOf(TaskOutcome.Done.class);
        verify(repository).recordProfesiaPositions(7, Map.of("profesia:1", SELLER, "profesia:2", List.of()));
        verify(repository).recordProfesiaPositions(9, Map.of("profesia:3", SELLER));
        verify(repository).recordEmployerLinks(7, Map.of("profesia:1", EMPLOYER_JOB));
    }

    @Test
    void unavailableProfesiaKeepsWhatWasReadAndRetries() {
        when(repository.uncheckedProfesiaAds(anyInt())).thenReturn(List.of(
                new PortalListRepository.UncheckedAd(7, "profesia:1", "https://www.profesia.sk/O1"),
                new PortalListRepository.UncheckedAd(7, "profesia:2", "https://www.profesia.sk/O2")));
        when(pages.positions("https://www.profesia.sk/O1")).thenReturn(facts(SELLER, null));
        when(pages.positions("https://www.profesia.sk/O2")).thenReturn(Optional.empty());

        TaskOutcome outcome = handler.handle(task());

        assertThat(outcome).isInstanceOf(TaskOutcome.Retry.class);
        verify(repository).recordProfesiaPositions(7, Map.of("profesia:1", SELLER));
    }

    private static Optional<ProfesiaPages.AdFacts> facts(List<ProfesiaPosition> positions, String employerUrl) {
        return Optional.of(new ProfesiaPages.AdFacts(positions, employerUrl));
    }

    private static TaskRecord task() {
        return new TaskRecord(0, ProfesiaPositionsHandler.TYPE, "{}", 0);
    }
}
