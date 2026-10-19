package com.roleorienta.api.admin;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/**
 * Данные запросов и ответов администратора (§132).
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * Вход администратора.
     *
     * @param username имя
     * @param password пароль
     */
    public record AdminCredentials(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * Вошедший администратор.
     *
     * @param username имя
     */
    public record Admin(String username) {
    }

    /**
     * Показатели ядра страны по дням.
     *
     * @param country   страна таблицы: код ISO, {@code *} — весь мир, пусто — без страны
     * @param countries страны, за которые есть показатели
     * @param days      дни, новые первыми ({@code 2026-10-18})
     * @param rows      строки: показатель и разрез, значения по дням
     */
    public record MetricsTable(String country, List<String> countries, List<String> days, List<MetricRow> rows) {
    }

    /**
     * Строка показателя.
     *
     * @param metric    код показателя (технический документ §11)
     * @param dimension разрез; пусто — итог показателя
     * @param values    значения по дням {@link MetricsTable#days()}; {@code null} — за день строки нет
     */
    public record MetricRow(String metric, String dimension, List<Long> values) {
    }

    /**
     * Состояние ядра сейчас (§142): видно, что ядро остановилось.
     *
     * @param checkedAt момент проверки (часы БД)
     * @param reads     чтения источников
     * @param tasks     задания
     * @param warnings  тревоги: {@code NO_RECENT_READ} — успешного чтения нет дольше 2 ч; {@code QUEUE_STALLED} —
     *                  задание ждёт в очереди дольше 2 ч
     */
    public record CoreStatus(Instant checkedAt, ReadStats reads, TaskStats tasks, List<String> warnings) {
    }

    /**
     * Чтения источников.
     *
     * @param lastSuccessAt  конец последнего чтения, полного или неполного; {@code null} — чтений не было
     * @param lastHour       чтений, полных или неполных, за последний час
     * @param failedLastHour отказов чтения за последний час
     */
    public record ReadStats(Instant lastSuccessAt, long lastHour, long failedLastHour) {
    }

    /**
     * Задания.
     *
     * @param queued         в очереди ({@code QUEUED})
     * @param oldestQueuedAt создано самое старое из них; {@code null} — очередь пуста
     * @param failedLastDay  не выполнены после всех попыток за сутки ({@code FAILED})
     */
    public record TaskStats(long queued, Instant oldestQueuedAt, long failedLastDay) {
    }
}
