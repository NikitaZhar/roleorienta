package com.roleorienta.worker.match;

import java.util.Set;

/**
 * Оценка соответствия вакансии условию поиска по стране или формату (бизнес-описание §4.4,
 * технический документ §7) — та же, что в SQL выдачи ({@code DeliveryRepository.PORTION}); выдача считает её
 * запросом, здесь она для приёмочного сценария 10 на размеченном корпусе. Соответствие включает вакансию, явное
 * несоответствие исключает; неясный формат — включение с пометкой.
 */
public enum Fit {
    /** Соответствует. */
    MATCH,
    /** Явно указано иное — вакансия исключается. */
    MISMATCH,
    /** Неясно (только формат) — включается с пометкой. */
    UNCERTAIN;

    /** Территория без ограничения (worldwide). */
    static final String ANY_COUNTRY = "*";

    /**
     * Страна: хоть одна страна работы среди стран пользователя или территория без ограничения («*») —
     * соответствие; иначе — несоответствие, в том числе когда страна не определена (стран нет или только места без
     * ясной страны; решение владельца 2026-10-15, стенограмма §120, аудит §121).
     *
     * @param workCountries   страны выполнения работы
     * @param searchCountries страны пользователя
     * @return оценка
     */
    public static Fit country(Set<String> workCountries, Set<String> searchCountries) {
        return workCountries.contains(ANY_COUNTRY) || workCountries.stream().anyMatch(searchCountries::contains)
                ? MATCH : MISMATCH;
    }

    /**
     * Формат: не задан в условиях — соответствие; у вакансии не указан или противоречив — неясно;
     * совпадает или у вакансии гибрид — соответствие (гибрид подходит к любому формату условий; в условиях гибрида нет —
     * решения владельца 2026-10-16, стенограмма §124, §125); иначе — несоответствие.
     *
     * @param vacancyFormat формат вакансии; {@code null} — не указан
     * @param searchFormat  формат из условий; {@code null} — не задан
     * @return оценка
     */
    public static Fit format(WorkFormat vacancyFormat, WorkFormat searchFormat) {
        if (searchFormat == null) {
            return MATCH;
        }
        if (vacancyFormat == null || vacancyFormat == WorkFormat.CONFLICT) {
            return UNCERTAIN;
        }
        return vacancyFormat == searchFormat || vacancyFormat == WorkFormat.HYBRID ? MATCH : MISMATCH;
    }
}
