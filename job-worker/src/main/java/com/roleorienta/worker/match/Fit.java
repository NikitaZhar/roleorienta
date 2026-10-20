package com.roleorienta.worker.match;

import java.util.Set;

/**
 * Оценка соответствия вакансии условию поиска по стране или формату (бизнес-описание §4.4,
 * технический документ §7) — та же, что в SQL выдачи ({@code DeliveryRepository.PORTION}); выдача считает её
 * запросом, здесь она для приёмочного сценария 10 на размеченном корпусе. Соответствие включает вакансию, явное
 * несоответствие исключает; неясные страна или формат — включение с пометкой.
 */
public enum Fit {
    /** Соответствует. */
    MATCH,
    /** Явно указано иное — вакансия исключается. */
    MISMATCH,
    /** Неясно (страна не определена или формат не указан) — включается с пометкой. */
    UNCERTAIN;

    /**
     * Страна (решения владельца 2026-10-20, стенограмма §148): хоть одна страна работы среди стран пользователя —
     * соответствие; страна не определена (стран нет: «удалённо» без территории, место не распознано) — неясно,
     * включение с пометкой; иначе — несоответствие, в том числе только территория без ограничения («*», удалённо
     * «весь мир»): право работать удалённо из своей страны на иностранного работодателя приложение не устанавливает.
     *
     * @param workCountries   страны выполнения работы; «*» — территория без ограничения
     * @param searchCountries страны пользователя
     * @return оценка
     */
    public static Fit country(Set<String> workCountries, Set<String> searchCountries) {
        if (workCountries.isEmpty()) {
            return UNCERTAIN;
        }
        return workCountries.stream().anyMatch(searchCountries::contains) ? MATCH : MISMATCH;
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
