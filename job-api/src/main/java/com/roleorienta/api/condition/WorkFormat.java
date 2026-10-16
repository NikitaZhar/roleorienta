package com.roleorienta.api.condition;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Формат работы в условиях поиска (бизнес-описание §3); условие необязательное ({@code null} — любой, «Any»). Гибрида
 * в условиях нет (решения владельца 2026-10-16, стенограмма §124, §125): гибридные вакансии подходят к любому формату;
 * {@code "HYBRID"} в запросе принимается как «любой» ({@link #fromJson}).
 */
public enum WorkFormat {
    /** Офис. */
    OFFICE,
    /** Удалённо. */
    REMOTE;

    private static final String HYBRID = "HYBRID";

    /**
     * Значение формата из JSON запроса. {@link JsonCreator} указывает Jackson (преобразование JSON в объекты) создавать
     * значение этим методом, а не по имени константы: так {@code "HYBRID"} (прежний формат условий) становится
     * «любым форматом» без ошибки запроса (решение владельца 2026-10-16, §125).
     * https://github.com/FasterXML/jackson-annotations/wiki/Jackson-Annotations
     *
     * @param value значение из JSON
     * @return формат; {@code null} — любой (для {@code "HYBRID"})
     * @throws IllegalArgumentException неизвестное значение — ошибка запроса {@code 400}
     */
    @JsonCreator
    public static WorkFormat fromJson(String value) {
        return HYBRID.equals(value) ? null : valueOf(value);
    }
}
