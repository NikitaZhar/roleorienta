package com.roleorienta.worker.extract;

import com.roleorienta.core.domain.SeniorityLevel;

/**
 * Результат извлечения требования к опыту из текста описания (§6, A08).
 *
 * <p>Уровень и число лет — раздельно (A08). {@code level} никогда не {@code null}
 * (при отсутствии сигнала — {@link SeniorityLevel#UNKNOWN}); {@code yearsMin} —
 * {@code null}, если число лет в тексте не указано.</p>
 *
 * @param level    уровень опыта (seniority)
 * @param yearsMin минимально требуемое число лет или {@code null}
 */
public record ExtractedExperience(SeniorityLevel level, Integer yearsMin) {
}
