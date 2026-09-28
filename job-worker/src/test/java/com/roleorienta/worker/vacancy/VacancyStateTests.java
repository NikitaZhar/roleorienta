package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.source.Source;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Состояние вакансии по её публикациям (бизнес-описание §4.3).
 */
class VacancyStateTests {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private static final FetchedPosting FETCHED =
            new FetchedPosting("101", "Java Developer", "https://example.com/101", null, null);

    private final Vacancy vacancy = new Vacancy("Java Developer", "https://example.com/101", NOW);

    private final JobPosting posting = new JobPosting(new Source("greenhouse", "acme"), vacancy, FETCHED, NOW);

    /**
     * Нет в полном чтении — повторная проверка; на третьем полном чтении подряд — закрыта.
     */
    @Test
    void closesOnThirdMissingCompleteRead() {
        posting.markMissing(3, NOW);
        posting.markMissing(3, NOW);
        vacancy.refreshState(List.of(posting), NOW);
        assertThat(vacancy.getState()).isEqualTo(VacancyState.NEEDS_RECHECK);

        posting.markMissing(3, NOW);
        vacancy.refreshState(List.of(posting), NOW);
        assertThat(vacancy.getState()).isEqualTo(VacancyState.CLOSED);
    }

    /**
     * Отказ источника не закрывает вакансию.
     */
    @Test
    void unavailableSourceNeedsRecheck() {
        posting.markUnconfirmed();
        vacancy.refreshState(List.of(posting), NOW);

        assertThat(vacancy.getState()).isEqualTo(VacancyState.NEEDS_RECHECK);
    }

    /**
     * Закрытая публикация появилась снова — та же вакансия снова актуальна.
     */
    @Test
    void reappearedPostingReopensVacancy() {
        for (int read = 0; read < 3; read++) {
            posting.markMissing(3, NOW);
        }
        vacancy.refreshState(List.of(posting), NOW);

        posting.update(FETCHED, NOW);
        vacancy.refreshState(List.of(posting), NOW);

        assertThat(vacancy.getState()).isEqualTo(VacancyState.ACTIVE);
    }
}
