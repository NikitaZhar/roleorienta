package com.roleorienta.api.report;

/**
 * Статус жалобы в очереди модерации (§46). Пользователь создаёт жалобу в {@link #OPEN};
 * {@link #RESOLVED}/{@link #DISMISSED} выставляет разбор (следующий срез).
 */
public enum PostingReportStatus {
    OPEN,
    RESOLVED,
    DISMISSED
}
