package com.roleorienta.worker.extract;

import com.roleorienta.core.domain.LanguageMention;
import com.roleorienta.core.domain.LanguageModality;

/**
 * Результат извлечения одного языкового требования из текста описания (§6, A07).
 *
 * <p>Факт упоминания и обязательность — раздельно (A07). Фрагмент — предложение, в
 * котором найден язык (подтверждение для карточки и повторной проверки).</p>
 *
 * @param languageCode код языка (ISO 639-1, напр. {@code en})
 * @param mentioned    факт упоминания
 * @param modality     обязательность
 * @param fragment     предложение-подтверждение или {@code null}
 */
public record ExtractedLanguage(String languageCode, LanguageMention mentioned,
                                LanguageModality modality, String fragment) {
}
