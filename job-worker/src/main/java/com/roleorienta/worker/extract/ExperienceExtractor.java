package com.roleorienta.worker.extract;

import com.roleorienta.core.domain.SeniorityLevel;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Извлечение требования к опыту из текста описания правилами (§6, A08; ADR-13 — без ML).
 *
 * <p>Уровень (seniority) и число лет — раздельно (A08). Уровень определяется по явным
 * словам (границы слова, без регистра): {@code junior} → JUNIOR;
 * {@code senior} → SENIOR; {@code medior}/{@code mid-level}/{@code intermediate} →
 * MEDIOR. Если найден ровно один уровень — он и берётся; если ни одного или сразу
 * несколько разных (напр. «junior or senior») — {@link SeniorityLevel#UNKNOWN}
 * (обязательность конкретного уровня из текста не следует — не угадываем).</p>
 *
 * <p>Число лет извлекается только по типовым конструкциям, привязанным к требованию,
 * чтобы не принять произвольное «N лет» за опыт:
 * {@code «N+ years/yrs»} (плюс подразумевает минимум) и
 * {@code «N years of experience»} (в т.ч. нижняя граница диапазона {@code «N-M years
 * of experience»} и {@code «at least N years of experience»}). Берётся первое по тексту
 * совпадение как минимально требуемое число лет; если конструкции нет — {@code null}
 * (например, «founded 10 years ago» опытом не считается). Верхняя граница диапазона в
 * этот срез не хранится.</p>
 *
 * <p>Правила детерминированы; словари уровней и конструкции лет намеренно малы (пилот) и
 * ведутся как данные. Качество ограничено их полнотой (ADR-13).</p>
 */
@Component
public class ExperienceExtractor {

    /** Уровень → слова-признаки (в нижнем регистре, ищутся по границам слова). */
    private static final Map<SeniorityLevel, List<String>> LEVEL_CUES = Map.of(
            SeniorityLevel.JUNIOR, List.of("junior"),
            SeniorityLevel.MEDIOR, List.of("medior", "mid-level", "mid level", "midlevel", "intermediate"),
            SeniorityLevel.SENIOR, List.of("senior"));

    /** «N years of experience» (в т.ч. «N-M years of experience», «at least N years of experience»). */
    private static final Pattern YEARS_EXPERIENCE = Pattern.compile(
            "(\\d+)\\s*\\+?\\s*(?:-\\s*\\d+)?\\s*(?:years?|yrs?)\\s+(?:of\\s+)?experience",
            Pattern.CASE_INSENSITIVE);

    /** «N+ years» — знак «плюс» подразумевает минимально требуемый опыт. */
    private static final Pattern YEARS_PLUS = Pattern.compile(
            "(\\d+)\\s*\\+\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);

    /**
     * Извлекает требование к опыту из текста описания.
     *
     * @param description текст описания или {@code null}
     * @return уровень (никогда не {@code null}; при отсутствии сигнала — {@code UNKNOWN})
     *         и минимальное число лет ({@code null}, если не указано)
     */
    public ExtractedExperience extract(String description) {
        if (description == null || description.isBlank()) {
            return new ExtractedExperience(SeniorityLevel.UNKNOWN, null);
        }
        return new ExtractedExperience(extractLevel(description), extractYears(description));
    }

    /**
     * Определяет уровень: ровно один найденный → он; ноль или несколько разных → UNKNOWN.
     */
    private SeniorityLevel extractLevel(String description) {
        String lower = description.toLowerCase();
        Set<SeniorityLevel> found = EnumSet.noneOf(SeniorityLevel.class);
        for (Map.Entry<SeniorityLevel, List<String>> level : LEVEL_CUES.entrySet()) {
            for (String cue : level.getValue()) {
                if (containsWord(lower, cue)) {
                    found.add(level.getKey());
                    break;
                }
            }
        }
        return found.size() == 1 ? found.iterator().next() : SeniorityLevel.UNKNOWN;
    }

    /**
     * Возвращает минимально требуемое число лет по первой встреченной типовой
     * конструкции, либо {@code null}.
     */
    private Integer extractYears(String description) {
        Matcher experience = YEARS_EXPERIENCE.matcher(description);
        boolean hasExperience = experience.find();
        Matcher plus = YEARS_PLUS.matcher(description);
        boolean hasPlus = plus.find();
        if (hasExperience && (!hasPlus || experience.start() <= plus.start())) {
            return Integer.valueOf(experience.group(1));
        }
        if (hasPlus) {
            return Integer.valueOf(plus.group(1));
        }
        return null;
    }

    /** Вхождение слова с учётом границ (чтобы {@code senior} ≠ {@code seniority}). */
    private boolean containsWord(String lowerText, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lowerText).find();
    }
}
