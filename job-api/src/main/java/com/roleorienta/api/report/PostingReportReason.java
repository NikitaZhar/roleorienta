package com.roleorienta.api.report;

/**
 * Причина жалобы на публикацию (§46). {@link #OTHER} — свободная причина, обычно с
 * пояснением в комментарии.
 */
public enum PostingReportReason {
    BROKEN_LINK,
    DUPLICATE,
    OUTDATED,
    OTHER
}
