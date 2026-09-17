package com.roleorienta.core.domain;

/**
 * Отношение вакансии к навыку (§6, A08): запрашивается ли он или, наоборот, назван как
 * ненужный/уходящий. Извлекается отдельно от обязательности {@link RequirementModality}
 * (A08: «отрицания/миграции извлекаются отдельно»).
 *
 * <p>{@code REQUESTED} — навык запрашивается (обычный случай; насколько — говорит
 * {@code modality}); {@code NEGATED} — явно назван как ненужный для роли
 * («X is not required», «no X needed»); {@code MIGRATION} — работодатель уходит от
 * технологии («moving away from X», «legacy X», «phasing out X»). Для {@code NEGATED} и
 * {@code MIGRATION} обязательность не утверждается ({@code modality = UNSPECIFIED}) —
 * это не требование, а сигнал «не считать обязательным пробелом».</p>
 */
public enum SkillStance {
    REQUESTED,
    NEGATED,
    MIGRATION
}
