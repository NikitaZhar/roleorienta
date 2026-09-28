package com.roleorienta.worker.http;

import java.time.Duration;
import java.util.Optional;

/**
 * Бюджет запросов к хосту: очередь запросов, общая для всех реплик.
 */
public interface HostBudget {

    /**
     * Резервирует место в очереди запросов к хосту.
     *
     * @param host хост запроса
     * @return сколько ждать до своего запроса; пусто — очередь длиннее
     *         {@link PolitenessProperties#hostMaxWait()}, запрос не резервируется
     */
    Optional<Duration> reserve(String host);

    /**
     * Хост попросил подождать ({@code Retry-After}): следующие запросы — не раньше срока.
     *
     * @param host  хост
     * @param delay сколько ждать
     */
    default void backOff(String host, Duration delay) {
    }
}
