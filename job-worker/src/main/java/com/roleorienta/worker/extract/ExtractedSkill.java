package com.roleorienta.worker.extract;

import com.roleorienta.core.domain.RequirementModality;

/**
 * Результат извлечения одного навыка из текста описания (§6, A08).
 *
 * <p>Имя навыка — каноническое (из таксономии), обязательность — по формулировке,
 * фрагмент — предложение-подтверждение (для карточки и повторной проверки).</p>
 *
 * @param skill    каноническое имя навыка (напр. {@code PostgreSQL})
 * @param modality обязательность требования
 * @param fragment предложение-подтверждение или {@code null}
 */
public record ExtractedSkill(String skill, RequirementModality modality, String fragment) {
}
