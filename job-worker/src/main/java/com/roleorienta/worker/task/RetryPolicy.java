package com.roleorienta.worker.task;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Интервал до следующей попытки: {@code initial × 2^(attempt−1)}, не больше {@code max}, плюс
 * случайная надбавка до {@code jitterRatio}; не меньше минимального срока, указанного источником.
 * Чистая логика без инфраструктуры.
 */
public class RetryPolicy {

    private static final int MAX_DOUBLINGS = 30;

    private final TaskProperties.Backoff backoff;
    private final DoubleSupplier random;

    /**
     * @param backoff настройки интервалов
     * @param random  источник случайного числа в [0, 1) для надбавки
     */
    public RetryPolicy(TaskProperties.Backoff backoff, DoubleSupplier random) {
        this.backoff = backoff;
        this.random = random;
    }

    /**
     * @param attempt      номер неудачной попытки, начиная с 1
     * @param minimumDelay минимальный срок от источника; {@link Duration#ZERO} — нет
     * @return интервал до следующей попытки
     */
    public Duration delay(int attempt, Duration minimumDelay) {
        int doublings = Math.min(Math.max(attempt - 1, 0), MAX_DOUBLINGS);
        long baseMillis = Math.min(backoff.initial().toMillis() << doublings, backoff.max().toMillis());
        long jitterMillis = (long) (baseMillis * backoff.jitterRatio() * random.getAsDouble());
        Duration delay = Duration.ofMillis(baseMillis + jitterMillis);
        return delay.compareTo(minimumDelay) >= 0 ? delay : minimumDelay;
    }
}
