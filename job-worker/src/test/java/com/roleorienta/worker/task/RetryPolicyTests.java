package com.roleorienta.worker.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Интервалы повторов: удвоение, потолок, надбавка, минимальный срок источника.
 */
class RetryPolicyTests {

    private static final TaskProperties.Backoff BACKOFF =
            new TaskProperties.Backoff(Duration.ofMinutes(5), Duration.ofHours(6), 0.1);

    private final RetryPolicy noJitter = new RetryPolicy(BACKOFF, () -> 0.0);

    /**
     * 5 мин, затем удвоение на каждой попытке.
     */
    @Test
    void doublesIntervalPerAttempt() {
        assertThat(noJitter.delay(1, Duration.ZERO)).isEqualTo(Duration.ofMinutes(5));
        assertThat(noJitter.delay(2, Duration.ZERO)).isEqualTo(Duration.ofMinutes(10));
        assertThat(noJitter.delay(7, Duration.ZERO)).isEqualTo(Duration.ofMinutes(320));
    }

    /**
     * Интервал не превышает потолок 6 ч даже при большом номере попытки.
     */
    @Test
    void capsIntervalAtMaximum() {
        assertThat(noJitter.delay(8, Duration.ZERO)).isEqualTo(Duration.ofHours(6));
        assertThat(noJitter.delay(100, Duration.ZERO)).isEqualTo(Duration.ofHours(6));
    }

    /**
     * Надбавка — до доли {@code jitterRatio} от интервала.
     */
    @Test
    void addsJitter() {
        RetryPolicy maxJitter = new RetryPolicy(BACKOFF, () -> 1.0);
        assertThat(maxJitter.delay(1, Duration.ZERO)).isEqualTo(Duration.ofSeconds(330));
    }

    /**
     * Минимальный срок источника (Retry-After) больше интервала — берётся он.
     */
    @Test
    void respectsMinimumDelayFromSource() {
        assertThat(noJitter.delay(1, Duration.ofHours(2))).isEqualTo(Duration.ofHours(2));
    }
}
