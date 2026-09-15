package com.roleorienta.core.domain;

/**
 * Тип фонового задания ({@link CrawlTask}).
 *
 * <p>Пока планировщик создаёт только {@code DISCOVER_PAGE} — проверку страницы
 * списка/ленты источника. Остальные типы из §6 технического документа
 * ({@code FETCH_POSTING}, {@code REPROCESS_POSTING},
 * {@code AGGREGATE_COMPANY_PROFILE}, {@code MATCH_SUBSCRIPTIONS},
 * {@code DISCOVER_EMPLOYER}) добавляются вместе со своими обработчиками.</p>
 */
public enum CrawlTaskType {
    DISCOVER_PAGE
}
