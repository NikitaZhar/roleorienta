package com.roleorienta.worker.extract;

import com.roleorienta.core.domain.RequirementModality;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Извлечение технологий/навыков из текста описания правилами (§6, A08; ADR-13 — без ML).
 *
 * <p>Навык ищется по курируемой таксономии (газеттиру): каноническое имя → список
 * алиасов/вариантов написания. Алиасы сводятся к одному навыку
 * ({@code Postgres}/{@code PostgreSQL} → {@code PostgreSQL}) — это отличие «алиасов
 * одного навыка» от «связанных навыков» (A08); связи между навыками (Java↔JVM) и
 * иерархия таксономии в этот срез не вводятся. Для каждого найденного навыка —
 * строка {@link ExtractedSkill} с обязательностью по формулировке предложения:
 * «required/must/mandatory…» → {@link RequirementModality#REQUIRED}; «is a plus/nice
 * to have…» → {@link RequirementModality#PREFERRED}; иначе → {@code UNSPECIFIED}.</p>
 *
 * <p>Две ловушки A08 обрабатываются осознанно и консервативно (лучше не утверждать
 * обязательность, чем выдумать её):</p>
 * <ul>
 *   <li><b>Альтернативы.</b> «Java or Kotlin is required» не создаёт двух обязательных
 *       пробелов: предложение с союзом «or» даёт обоим навыкам {@code UNSPECIFIED}
 *       (обязательность конкретного навыка из альтернативы не следует).</li>
 *   <li><b>Отрицания/миграции.</b> «C# is not required», «moving away from X» не дают
 *       {@code REQUIRED} — ставится {@code UNSPECIFIED}.</li>
 * </ul>
 *
 * <p>Границы токенов учитывают технические названия с не-буквенными символами
 * ({@code C++}, {@code C#}, {@code .NET}, {@code Node.js}): совпадение ограничено
 * лукахедом/лукбехайндом по набору «символов токена», а не {@code \\b} (для которого
 * {@code +}/{@code #}/{@code .} — границы, и {@code C} ошибочно совпало бы с началом
 * {@code C++}). Разбиение на предложения не рвёт токены с точкой ({@code Node.js}):
 * точка считается концом предложения только перед пробелом или концом текста.</p>
 *
 * <p>Набор навыков и словари формулировок намеренно малы (пилот) и ведутся как данные.
 * Набор консервативен: неоднозначные однобуквенные/словарные коллизии в него не
 * включены (напр. язык {@code Go} — только по алиасу {@code golang}, без «go», чтобы не
 * ловить английский глагол). Качество ограничено полнотой словарей (ADR-13); правила
 * версионируются ({@link #VERSION}) для воспроизводимости и пересчёта.</p>
 */
@Component
public class SkillExtractor {

    /** Версия правил извлечения навыков; меняется при изменении таксономии/словарей. */
    public static final String VERSION = "skill-rules-1";

    /** Символы, из которых состоит технический токен (для границ совпадения). */
    private static final String TOKEN_CHARS = "A-Za-z0-9+#.";

    /**
     * Курируемая таксономия: каноническое имя навыка → алиасы для поиска (в нижнем
     * регистре). {@link LinkedHashMap} — детерминированный порядок обхода (и вывода).
     */
    private static final Map<String, List<String>> SKILLS = new LinkedHashMap<>();

    static {
        SKILLS.put("Java", List.of("java"));
        SKILLS.put("Kotlin", List.of("kotlin"));
        SKILLS.put("Spring", List.of("spring boot", "spring"));
        SKILLS.put("PostgreSQL", List.of("postgresql", "postgres"));
        SKILLS.put("SQL", List.of("sql"));
        SKILLS.put("Docker", List.of("docker"));
        SKILLS.put("Kubernetes", List.of("kubernetes", "k8s"));
        SKILLS.put("AWS", List.of("aws"));
        SKILLS.put("Python", List.of("python"));
        SKILLS.put("JavaScript", List.of("javascript"));
        SKILLS.put("TypeScript", List.of("typescript"));
        SKILLS.put("Node.js", List.of("node.js", "nodejs"));
        SKILLS.put("React", List.of("react"));
        SKILLS.put("Go", List.of("golang"));
        SKILLS.put("C++", List.of("c++"));
        SKILLS.put("C#", List.of("c#"));
        SKILLS.put(".NET", List.of(".net"));
        SKILLS.put("Git", List.of("git"));
    }

    /** Скомпилированные шаблоны совпадения по канону: имя → шаблон «алиас в границах токена». */
    private static final Map<String, Pattern> PATTERNS = compilePatterns();

    /** Формулировки обязательности. */
    private static final List<String> REQUIRED_CUES = List.of(
            "required", "must ", "mandatory", "proficient", "proficiency",
            "expert", "expertise", "strong command", "solid command");

    /** Формулировки желательности. */
    private static final List<String> PREFERRED_CUES = List.of(
            "is a plus", "a plus", "preferred", "nice to have", "nice-to-have",
            "desirable", "advantage", "advantageous", "bonus", "beneficial");

    /** Явные отрицания/миграции (не дают REQUIRED). */
    private static final List<String> NEGATION_CUES = List.of(
            "not required", "not mandatory", "not necessary", "no need",
            "no longer", "moving away", "away from", "deprecat", "legacy");

    /** Союз-альтернатива в предложении (по границам слова), напр. «Java or Kotlin». */
    private static final Pattern ALTERNATIVE = Pattern.compile("\\bor\\b");

    /** Границы предложения: точка/!/? как конец — только перед пробелом или концом текста. */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.!?\\n]+(?=\\s|$)");

    /**
     * Извлекает навыки из текста описания.
     *
     * @param description текст описания или {@code null}
     * @return список найденных навыков в детерминированном порядке таксономии (пустой,
     *         если описания нет или навыки не упомянуты)
     */
    public List<ExtractedSkill> extract(String description) {
        List<ExtractedSkill> result = new ArrayList<>();
        if (description == null || description.isBlank()) {
            return result;
        }
        String[] sentences = SENTENCE_SPLIT.split(description);
        for (Map.Entry<String, List<String>> skill : SKILLS.entrySet()) {
            Pattern pattern = PATTERNS.get(skill.getKey());
            RequirementModality best = null;
            String fragment = null;
            for (String raw : sentences) {
                if (!pattern.matcher(raw).find()) {
                    continue;
                }
                String sentence = raw.strip();
                RequirementModality modality = modalityOf(sentence);
                // Первое упоминание задаёт значение; более сильная обязательность его повышает.
                if (best == null || rank(modality) > rank(best)) {
                    best = modality;
                    fragment = sentence;
                }
            }
            if (best != null) {
                result.add(new ExtractedSkill(skill.getKey(), best, fragment));
            }
        }
        return result;
    }

    /**
     * Определяет обязательность по формулировке предложения. Порядок: альтернатива или
     * отрицание → {@code UNSPECIFIED}; иначе обязательно → желательно → не уточнено.
     */
    private RequirementModality modalityOf(String sentence) {
        String lower = sentence.toLowerCase();
        if (ALTERNATIVE.matcher(lower).find() || containsAny(lower, NEGATION_CUES)) {
            return RequirementModality.UNSPECIFIED;
        }
        if (containsAny(lower, REQUIRED_CUES)) {
            return RequirementModality.REQUIRED;
        }
        if (containsAny(lower, PREFERRED_CUES)) {
            return RequirementModality.PREFERRED;
        }
        return RequirementModality.UNSPECIFIED;
    }

    /** Ранг обязательности для выбора самой сильной среди нескольких упоминаний. */
    private int rank(RequirementModality modality) {
        return switch (modality) {
            case REQUIRED -> 3;
            case PREFERRED -> 2;
            case UNSPECIFIED -> 1;
        };
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

    /**
     * Компилирует по одному шаблону на канонический навык: альтернатива всех алиасов в
     * границах токена, без учёта регистра. Границы заданы негативными лукбехайндом и
     * лукахедом по {@link #TOKEN_CHARS}, чтобы {@code java} не совпадало внутри
     * {@code javascript}, а {@code c}/{@code sql} — внутри {@code c++}/{@code postgresql}.
     */
    private static Map<String, Pattern> compilePatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> skill : SKILLS.entrySet()) {
            StringBuilder alternation = new StringBuilder();
            for (String alias : skill.getValue()) {
                if (alternation.length() > 0) {
                    alternation.append('|');
                }
                alternation.append(Pattern.quote(alias));
            }
            String regex = "(?<![" + TOKEN_CHARS + "])(?:" + alternation + ")(?![" + TOKEN_CHARS + "])";
            patterns.put(skill.getKey(), Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return patterns;
    }
}
