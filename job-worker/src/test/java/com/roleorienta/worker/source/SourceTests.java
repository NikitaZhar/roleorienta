package com.roleorienta.worker.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Доступность источника: серия временных отказов, срок до «недоступен», постоянный отказ,
 * восстановление.
 */
class SourceTests {

    private static final Duration UNAVAILABLE_AFTER = Duration.ofDays(7);
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private final Source source = new Source("workday", "board");

    /**
     * Временные отказы — «временные отказы» до конца срока, от начала серии; на седьмой день —
     * «недоступен».
     */
    @Test
    void temporaryFailuresBecomeUnavailableAfterPeriod() {
        source.markFailure(true, START, UNAVAILABLE_AFTER);
        source.markFailure(true, START.plus(Duration.ofDays(6)), UNAVAILABLE_AFTER);
        assertThat(source.getAvailability()).isEqualTo(Availability.TEMP_FAILING);

        source.markFailure(true, START.plus(UNAVAILABLE_AFTER), UNAVAILABLE_AFTER);

        assertThat(source.getAvailability()).isEqualTo(Availability.UNAVAILABLE);
    }

    /**
     * Ответ списка прерывает серию: следующий отказ начинает новую.
     */
    @Test
    void readResetsFailureSeries() {
        source.markFailure(true, START, UNAVAILABLE_AFTER);
        source.markRead();
        assertThat(source.getAvailability()).isEqualTo(Availability.OK);

        source.markFailure(true, START.plus(UNAVAILABLE_AFTER), UNAVAILABLE_AFTER);

        assertThat(source.getAvailability()).isEqualTo(Availability.TEMP_FAILING);
    }

    /**
     * Постоянный отказ (доступ запрещён, страница удалена) — сразу «недоступен».
     */
    @Test
    void permanentFailureIsUnavailableAtOnce() {
        source.markFailure(false, START, UNAVAILABLE_AFTER);

        assertThat(source.getAvailability()).isEqualTo(Availability.UNAVAILABLE);
    }
}
