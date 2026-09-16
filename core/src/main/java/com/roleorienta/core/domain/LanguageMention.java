package com.roleorienta.core.domain;

/**
 * Факт упоминания языка в вакансии (§6, A07) — независим от модальности.
 *
 * <p>{@code YES} — язык в тексте упомянут; {@code NO} — упомянут явно как ненужный
 * («German is not required»); {@code UNKNOWN} — извлечение не выполнено или текст
 * не удалось разобрать. «Не упомянут вовсе» отражается отсутствием строки
 * {@code posting_language}, а не значением {@code NO} (A07: ошибка ≠ «нет»).</p>
 */
public enum LanguageMention {
    YES,
    NO,
    UNKNOWN
}
