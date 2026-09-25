package com.roleorienta.worker.region;

/**
 * Карьерная страница компании и её тип (§95).
 *
 * @param url           адрес страницы вакансий (или доски системы найма)
 * @param system        код системы найма, {@code schema-org}, {@code own-page} или {@code none}
 * @param postings      число вакансий; {@code null} — не считали (для системы нет адаптера)
 * @param nichePostings из них в нише пилота; {@code null} — не считали
 */
public record CareerPage(String url, String system, Integer postings, Integer nichePostings) {

    /** Тип «своя страница со стандартной разметкой вакансий». */
    public static final String SCHEMA_ORG = "schema-org";

    /** Тип «своя страница без разметки». */
    public static final String OWN_PAGE = "own-page";

    /** Раздела вакансий на сайте не найдено. */
    public static final String NONE = "none";
}
