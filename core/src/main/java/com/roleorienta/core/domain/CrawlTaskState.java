package com.roleorienta.core.domain;

/**
 * Состояние фонового задания ({@link CrawlTask}) в его жизненном цикле:
 * запланировано, выполняется, успешно завершено или провалено.
 */
public enum CrawlTaskState {
    SCHEDULED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
