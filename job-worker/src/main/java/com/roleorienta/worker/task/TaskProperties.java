package com.roleorienta.worker.task;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки заданий ({@code app.task.*} в {@code application.yml}); значения по умолчанию —
 * технический документ §17.
 *
 * @param maxAttempts      сколько неудачных попыток допускается до окончательной неудачи
 * @param backoff          интервалы повторов
 * @param lease            аренда выполнения: не завершённое за этот срок задание снова ставится
 *                         в очередь (процесс мог упасть)
 * @param requeueBatchSize максимум заданий, перепоставленных за один тик
 */
@ConfigurationProperties("app.task")
public record TaskProperties(
        @DefaultValue("8") int maxAttempts,
        @DefaultValue Backoff backoff,
        @DefaultValue("10m") Duration lease,
        @DefaultValue("100") int requeueBatchSize) {

    /**
     * Интервалы повторов: первый, удвоение на каждой попытке, потолок и случайная надбавка,
     * чтобы повторы многих заданий не приходили одновременно.
     *
     * @param initial     интервал после первой неудачи
     * @param max         потолок интервала
     * @param jitterRatio доля случайной надбавки к интервалу (0.1 — до +10%)
     */
    public record Backoff(
            @DefaultValue("5m") Duration initial,
            @DefaultValue("6h") Duration max,
            @DefaultValue("0.1") double jitterRatio) {
    }
}
