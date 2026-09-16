package com.roleorienta.core.domain;

/**
 * Формат работы по месту (§6): удалённо, гибрид или не указан.
 *
 * <p>{@code UNKNOWN} — источник о формате не сообщил (в строке локации нет слова
 * {@code remote}/{@code hybrid}). Отсутствие слова не означает офис — догадки не
 * делаются, ставится явное «неизвестно» (A01/§6). Значение {@code null} в колонке
 * {@code work_modality} — локации нет вовсе (отличается от {@code UNKNOWN}).</p>
 */
public enum WorkModality {
    REMOTE,
    HYBRID,
    UNKNOWN
}
