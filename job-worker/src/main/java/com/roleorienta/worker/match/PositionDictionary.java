package com.roleorienta.worker.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Словарь позиций — данные с версией (файл {@code positions.json} приложения; технический документ
 * §6, §16.13): для каждой позиции эквивалентные названия, роли и признаки специализации,
 * исключающие слова. Словарь ведётся как данные: новая позиция или синоним — правка файла и
 * версии, код не меняется; смена версии пересчитывает соответствия всех вакансий.
 */
@Component
public class PositionDictionary {

    private static final String RESOURCE = "/positions.json";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern DASHES = Pattern.compile("[\\p{Pd}/|,()\\[\\]:;]+");
    /** Граница слова: до термина нет буквы, цифры, {@code +}, {@code #} (C++, C#). */
    private static final String BEFORE = "(?<![\\p{L}\\p{N}+#])";
    /** Граница слова: после термина нет буквы, цифры, {@code +}, {@code #} или точки внутри слова (.NET, Node.js). */
    private static final String AFTER = "(?![\\p{L}\\p{N}+#]|\\.[\\p{L}\\p{N}])";

    private final String version;
    private final int contentHits;
    private final List<Position> positions;

    /**
     * Загружает словарь из ресурса приложения.
     */
    public PositionDictionary() {
        this(readResource());
    }

    /**
     * @param dictionary словарь в формате {@code positions.json}
     */
    PositionDictionary(JsonNode dictionary) {
        this.version = dictionary.path("version").asText();
        this.contentHits = dictionary.path("contentHits").asInt();
        List<Position> loaded = new ArrayList<>();
        for (JsonNode position : dictionary.path("positions")) {
            loaded.add(new Position(position.path("code").asText(), position.path("name").asText(),
                    terms(position.path("titles")),
                    new Specialization(terms(position.path("roles")), terms(position.path("keywords"))),
                    terms(position.path("exclude"))));
        }
        this.positions = List.copyOf(loaded);
    }

    public String version() {
        return version;
    }

    public int contentHits() {
        return contentHits;
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

    private static List<Term> terms(JsonNode values) {
        List<Term> terms = new ArrayList<>();
        values.forEach(value -> terms.add(new Term(value.asText(), Pattern.compile(BEFORE
                + Pattern.quote(normalize(value.asText()).strip()) + AFTER))));
        return List.copyOf(terms);
    }

    private static JsonNode readResource() {
        try (InputStream input = PositionDictionary.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Position dictionary " + RESOURCE + " is missing");
            }
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("Position dictionary " + RESOURCE + " is not readable", exception);
        }
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
