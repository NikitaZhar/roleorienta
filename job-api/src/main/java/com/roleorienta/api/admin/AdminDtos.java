package com.roleorienta.api.admin;

import jakarta.validation.constraints.NotBlank;
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
}
