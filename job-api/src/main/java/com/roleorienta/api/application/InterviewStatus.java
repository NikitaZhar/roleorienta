package com.roleorienta.api.application;

/**
 * Состояние собеседования по отклику (§43). Назначенное собеседование —
 * {@link #SCHEDULED}; перенос меняет только время (статус остаётся {@code SCHEDULED}),
 * отмена переводит в терминальное {@link #CANCELLED}.
 */
public enum InterviewStatus {
    SCHEDULED,
    CANCELLED
}
