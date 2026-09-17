package com.roleorienta.worker.extract;

import com.roleorienta.core.domain.RequirementModality;
import com.roleorienta.core.domain.SkillStance;

/**
 * Результат извлечения одного навыка из текста описания (§6, A08).
 *
 * <p>Отношение к навыку ({@code stance} — запрос/отрицание/миграция) и обязательность
 * ({@code modality}) — раздельно (A08). Имя навыка — каноническое (из таксономии),
 * фрагмент — предложение-подтверждение.</p>
 *
 * @param skill    каноническое имя навыка (напр. {@code PostgreSQL})
 * @param stance   отношение к навыку (запрос/отрицание/миграция)
 * @param modality обязательность требования (значима при {@code stance = REQUESTED})
 * @param fragment предложение-подтверждение или {@code null}
 */
public record ExtractedSkill(String skill, SkillStance stance, RequirementModality modality,
                             String fragment) {
}
