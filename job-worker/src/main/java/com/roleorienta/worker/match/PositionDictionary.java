package com.roleorienta.worker.match;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Словарь позиций (технический документ §6, §16.13): для каждой позиции эквивалентные названия, роли и признаки
 * специализации, исключающие слова; общие настройки — сколько раз признак должен встретиться в тексте и признаки
 * специализаций вне словаря. Словарь хранится в БД ({@code position}, {@code position_term},
 * {@code position_dictionary}; стенограмма §159) и читается воркером в начале каждого задания сопоставления
 * ({@code MatchRepository.dictionary}); номер его версии растёт при каждом изменении и входит в версию
 * сопоставления — изменение пересчитывает соответствия всех вакансий.
 */
public class PositionDictionary {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern DASHES = Pattern.compile("[\\p{Pd}/|,()\\[\\]:;]+");
    /** Граница слова: до термина нет буквы, цифры, {@code +}, {@code #} (C++, C#). */
    private static final String BEFORE = "(?<![\\p{L}\\p{N}+#])";
    /** Граница слова: после термина нет буквы, цифры, {@code +}, {@code #} или точки внутри слова (.NET, Node.js). */
    private static final String AFTER = "(?![\\p{L}\\p{N}+#]|\\.[\\p{L}\\p{N}])";

    private final String version;
    private final int contentHits;
    private final List<Term> otherSpecializations;
    private final List<Position> positions;

    /**
     * @param version              версия словаря ({@code d<номер>})
     * @param contentHits          сколько раз признак специализации должен встретиться в тексте при роли в названии
     * @param otherSpecializations признаки специализаций вне словаря
     * @param positions            позиции
     */
    public PositionDictionary(String version, int contentHits, List<Term> otherSpecializations,
            List<Position> positions) {
        this.version = version;
        this.contentHits = contentHits;
        this.otherSpecializations = List.copyOf(otherSpecializations);
        this.positions = List.copyOf(positions);
    }

    public String version() {
        return version;
    }

    public int contentHits() {
        return contentHits;
    }

    /**
     * @return признаки специализаций вне словаря (Ruby, PHP …)
     */
    public List<Term> otherSpecializations() {
        return otherSpecializations;
    }

    public List<Position> positions() {
        return positions;
    }

    /**
     * Текст для сопоставления: нижний регистр, без диакритики, тире и разделители — пробелы.
     *
     * @param text исходный текст
     * @return нормализованный текст
     */
    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return DASHES.matcher(MARKS.matcher(decomposed).replaceAll("")).replaceAll(" ");
    }

    /**
     * @param text слово или словосочетание, как записано в словаре
     * @return термин с шаблоном поиска в нормализованном тексте
     */
    public static Term term(String text) {
        return new Term(text, Pattern.compile(BEFORE + Pattern.quote(normalize(text).strip()) + AFTER));
    }

    /**
     * Позиция словаря.
     *
     * @param code           код (ссылка из условий поиска)
     * @param name           название
     * @param titles         эквивалентные названия
     * @param specialization роли и признаки специализации
     * @param exclude        слова названия, исключающие позицию
     */
    public record Position(String code, String name, List<Term> titles, Specialization specialization,
            List<Term> exclude) {
    }

    /**
     * Роль и специализация: роль (developer, engineer …) в названии вместе с признаком (java,
     * spring …) в названии или тексте.
     *
     * @param roles    роли
     * @param keywords признаки специализации
     */
    public record Specialization(List<Term> roles, List<Term> keywords) {
    }

    /**
     * Термин словаря: как записан и шаблон поиска в нормализованном тексте — с учётом границ слов, к
     * которым относятся и {@code +}, {@code #} ({@code C++}, {@code C#}, {@code .NET}).
     *
     * @param text    как записан
     * @param pattern шаблон поиска
     */
    public record Term(String text, Pattern pattern) {
    }
}
