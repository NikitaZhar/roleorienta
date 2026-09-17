package com.roleorienta.core.domain;

/**
 * Обязательность структурированного требования (§6, A08) — технологии/навыки.
 *
 * <p>{@code REQUIRED} — навык обязателен («required», «must»); {@code PREFERRED} —
 * желателен («is a plus», «nice to have»); {@code UNSPECIFIED} — навык упомянут, но
 * обязательность из текста не следует. Догадки не делаются (контракт §3.10, ADR-13):
 * если из формулировки обязательность не ясна — либо навык назван в составе
 * альтернативы («Java or Kotlin»), либо назван с отрицанием/миграцией
 * («not required», «moving away from») — ставится {@code UNSPECIFIED}, а не
 * {@code REQUIRED}. Отдельное перечисление (не переиспользуется {@code LanguageModality}):
 * у языков своя модель A07, у навыков — своя A08; они развиваются независимо.</p>
 */
public enum RequirementModality {
    REQUIRED,
    PREFERRED,
    UNSPECIFIED
}
