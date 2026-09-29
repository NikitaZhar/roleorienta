package com.roleorienta.worker.match;

import java.util.Set;

/**
 * Оценка соответствия вакансии условию поиска по стране или формату (бизнес-описание §4.4,
 * технический документ §7): подтверждённое соответствие включает вакансию, явное несоответствие
 * исключает, неоднозначные или неполные сведения — включение с пометкой.
 */
public enum Fit {
    /** Соответствует. */
    MATCH,
    /** Явно указано иное — вакансия исключается. */
    MISMATCH,
    /** Неясно — включается с пометкой. */
    UNCERTAIN;

    /** Территория без ограничения (worldwide). */
    static final String ANY_COUNTRY = "*";

    /**
     * Страна: хоть одна страна работы среди стран пользователя (или территория без ограничения) —
     * соответствие; иначе есть место без ясной страны или стран нет вовсе — неясно; иначе все места
     * явно в других странах — несоответствие.
     *
     * @param workCountries   страны выполнения работы
     * @param uncertain       есть место без ясной страны
     * @param searchCountries страны пользователя
     * @return оценка
     */
    public static Fit country(Set<String> workCountries, boolean uncertain, Set<String> searchCountries) {
        if (workCountries.contains(ANY_COUNTRY) || workCountries.stream().anyMatch(searchCountries::contains)) {
            return MATCH;
        }
        return uncertain || workCountries.isEmpty() ? UNCERTAIN : MISMATCH;
    }

    /**
     * Формат: не задан в условиях — соответствие; у вакансии не указан или противоречив — неясно;
     * совпадает — соответствие; иначе — несоответствие.
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
        return vacancyFormat == searchFormat ? MATCH : MISMATCH;
    }
}
