package com.roleorienta.worker.match;

/**
 * Формат работы (бизнес-описание §3).
 */
public enum WorkFormat {
    /** Офис. */
    OFFICE,
    /** Гибрид. */
    HYBRID,
    /** Удалённо. */
    REMOTE,
    /** Сведения противоречат друг другу (разные источники или текст) — неопределённость. */
    CONFLICT
}
