package com.roleorienta.worker.extract;

import com.roleorienta.core.domain.LanguageMention;
import com.roleorienta.core.domain.LanguageModality;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Извлечение языковых требований из текста описания правилами (§6, A07; ADR-13 — без ML).
 *
 * <p>Для каждого языка из курируемого набора ищется упоминание по названиям (с учётом
 * границ слова, без регистра). Если язык найден — строка {@link ExtractedLanguage} с
 * {@link LanguageMention#YES} и модальностью по формулировке предложения:
 * «required/must/fluent…» → {@link LanguageModality#REQUIRED}; «is a plus/preferred…»
 * → {@link LanguageModality#PREFERRED}; иначе → {@link LanguageModality#UNSPECIFIED}.
 * Явное отрицание («not required») не даёт REQUIRED (остаётся UNSPECIFIED). Если язык
 * не найден — строки нет (это «не упомянут», а не ошибка). Правила детерминированы и
 * версионируются ({@link #VERSION}), чтобы результат воспроизводился и мог быть
 * пересчитан новой версией.</p>
 *
 * <p>Набор языков и словари формулировок намеренно малы (пилот, рынок DE/EN) и ведутся
 * как данные — пополняются по мере надобности. Качество ограничено полнотой этих
 * словарей (ADR-13).</p>
 */
@Component
public class LanguageExtractor {

    /** Версия правил извлечения языков; меняется при изменении набора/словарей. */
    public static final String VERSION = "lang-rules-1";

    /** Курируемый набор языков: код ISO 639-1 → названия/варианты для поиска. */
    private static final Map<String, List<String>> LANGUAGES = Map.of(
            "en", List.of("english"),
            "de", List.of("german", "deutsch"));

    /** Формулировки обязательности. */
    private static final List<String> REQUIRED_CUES = List.of(
            "required", "must ", "mandatory", "fluent", "fluency", "proficient",
            "proficiency", "native", "strong command", "excellent command");

    /** Формулировки желательности. */
    private static final List<String> PREFERRED_CUES = List.of(
            "is a plus", "a plus", "preferred", "nice to have", "nice-to-have",
            "desirable", "advantage", "advantageous", "bonus", "beneficial");

    /** Явные отрицания обязательности (проверяются раньше REQUIRED_CUES). */
    private static final List<String> NEGATION_CUES = List.of(
            "not required", "not mandatory", "not necessary", "no need");

    /** Границы предложения для выделения фрагмента-подтверждения. */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.!?\\n]+");

    /**
     * Извлекает языковые требования из текста описания.
     *
     * @param description текст описания или {@code null}
     * @return список найденных языков (пустой, если описания нет или языки не упомянуты)
     */
    public List<ExtractedLanguage> extract(String description) {
        List<ExtractedLanguage> result = new ArrayList<>();
        if (description == null || description.isBlank()) {
            return result;
        }
        String[] sentences = SENTENCE_SPLIT.split(description);
        for (Map.Entry<String, List<String>> language : LANGUAGES.entrySet()) {
            String fragment = firstSentenceMentioning(sentences, language.getValue());
            if (fragment != null) {
                result.add(new ExtractedLanguage(
                        language.getKey(), LanguageMention.YES, modalityOf(fragment), fragment));
            }
        }
        return result;
    }

    /**
     * Возвращает первое предложение, где по границам слова встречается любое из
     * названий языка, либо {@code null}, если язык не упомянут.
     */
    private String firstSentenceMentioning(String[] sentences, List<String> names) {
        for (String sentence : sentences) {
            String lower = sentence.toLowerCase();
            for (String name : names) {
                if (containsWord(lower, name)) {
                    return sentence.strip();
                }
            }
        }
        return null;
    }

    /** Проверяет вхождение слова с учётом границ (чтобы {@code german} ≠ {@code germany}). */
    private boolean containsWord(String lowerText, String word) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lowerText);
        return matcher.find();
    }

    /**
     * Определяет модальность по формулировке предложения. Порядок: отрицание →
     * обязательно → желательно → не уточнено. Отрицание не даёт REQUIRED.
     */
    private LanguageModality modalityOf(String sentence) {
        String lower = sentence.toLowerCase();
        if (containsAny(lower, NEGATION_CUES)) {
            return LanguageModality.UNSPECIFIED;
        }
        if (containsAny(lower, REQUIRED_CUES)) {
            return LanguageModality.REQUIRED;
        }
        if (containsAny(lower, PREFERRED_CUES)) {
            return LanguageModality.PREFERRED;
        }
        return LanguageModality.UNSPECIFIED;
    }

    /** Содержит ли текст хотя бы одну из формулировок (подстрокой). */
    private boolean containsAny(String lowerText, List<String> cues) {
        for (String cue : cues) {
            if (lowerText.contains(cue)) {
                return true;
            }
        }
        return false;
    }
}
