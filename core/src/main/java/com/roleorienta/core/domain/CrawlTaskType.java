package com.roleorienta.core.domain;

/**
 * Тип фонового задания ({@link CrawlTask}).
 *
 * <p>{@code DISCOVER_PAGE} — проверка страницы списка/ленты источника и обнаружение
 * публикаций; {@code FETCH_POSTING} — дозапрос детальной страницы одной публикации
 * и сохранение добранных полей. Остальные типы из §6 технического документа
 * ({@code REPROCESS_POSTING}, {@code AGGREGATE_COMPANY_PROFILE},
 * {@code MATCH_SUBSCRIPTIONS}, {@code DISCOVER_EMPLOYER}) добавляются вместе со
 * своими обработчиками.</p>
 */
public enum CrawlTaskType {
    DISCOVER_EMPLOYER,
    DISCOVER_PAGE,
    FETCH_POSTING
}
