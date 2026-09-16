package com.roleorienta.core.domain;

/**
 * Модальность языкового требования (§6, A07) — независима от факта упоминания.
 *
 * <p>{@code REQUIRED} — язык обязателен; {@code PREFERRED} — желателен («is a plus»);
 * {@code UNSPECIFIED} — язык упомянут, но обязательность не следует из текста
 * («German-speaking team» или «not required»). Догадки не делаются: если из
 * формулировки обязательность не ясна, ставится {@code UNSPECIFIED}, а не
 * {@code REQUIRED}.</p>
 */
public enum LanguageModality {
    REQUIRED,
    PREFERRED,
    UNSPECIFIED
}
