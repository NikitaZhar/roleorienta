package com.roleorienta.worker.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/**
 * Требования к языку в тексте вакансии (бизнес-описание §6; решения владельца 2026-10-21, стенограмма §158): какой
 * язык нужен и обязателен он или желателен. Слова — данные с версией (файл {@code languages.json}): названия языков,
 * признаки знания языка, признаки «желателен»; смена версии пересчитывает все вакансии.
 *
 * <ul>
 *   <li>Текст делится на части: блоки разметки (абзац, пункт списка, строка таблицы, перенос строки), маркеры
 *       списка и концы предложений.</li>
 *   <li>Язык засчитывается, если в той же части рядом с названием — до {@value #CUE_WINDOW} слов — есть признак
 *       знания языка («fluent», «B2», «znalosť» …) или вплотную — до {@value #MARKER_CUE_WINDOW} слов — признак
 *       «желателен» («German is a plus»). Без признака название не засчитывается: «Slovak Republic», «German
 *       company» — не требование к языку.</li>
 *   <li>Признак «желателен» относится к ближайшему засчитанному языку части (до {@value #MARKER_WINDOW} слов; при
 *       равном расстоянии — к обоим). Остальные — обязательны: «Fluent English» читается как требование (решение
 *       владельца 2026-10-21). Язык, встретившийся и обязательным, и желательным, — обязателен.</li>
 *   <li>Язык самого текста объявления не учитывается; текста нет — требований нет («не указано»).</li>
 * </ul>
 */
@Component
public class LanguageRequirements {

    /** Наибольшее расстояние (в словах) от названия языка до признака знания. */
    static final int CUE_WINDOW = 4;
    /** Наибольшее расстояние от названия языка до признака «желателен», чтобы язык засчитался без признака знания. */
    static final int MARKER_CUE_WINDOW = 2;
    /** Наибольшее расстояние от признака «желателен» до языка, к которому он относится. */
    static final int MARKER_WINDOW = 6;

    private static final String RESOURCE = "/languages.json";
    /** Блоки разметки, после которых кончается часть текста. */
    private static final String BLOCKS = "br, p, li, div, tr, td, h1, h2, h3, h4, h5, h6, ul, ol";
    /** Граница частей: перенос строки, маркер списка, конец предложения (точка, знак вопроса или восклицания, «;»). */
    private static final Pattern PART_END = Pattern.compile("[\\n•▪●◦]+|(?<=[.!?;])\\s+");
    private static final Pattern WORD = Pattern.compile("\\S+");
    /** Граница слова в нормализованном тексте: рядом нет буквы или цифры. */
    private static final String BEFORE = "(?<![\\p{L}\\p{N}])";
    private static final String AFTER = "(?![\\p{L}\\p{N}])";
    /** {@code *} в конце слова термина — любое окончание. */
    private static final String ANY_ENDING = "*";

    private final String version;
    private final Map<String, List<Pattern>> languages;
    private final List<Pattern> cues;
    private final List<Pattern> preferred;

    /**
     * Загружает слова из ресурса приложения.
     */
    public LanguageRequirements() {
        this(readResource());
    }

    /**
     * @param words слова в формате {@code languages.json}
     */
    LanguageRequirements(JsonNode words) {
        this.version = words.path("version").asText();
        Map<String, List<Pattern>> loaded = new TreeMap<>();
        words.path("languages").fields().forEachRemaining(language ->
                loaded.put(language.getKey(), patterns(language.getValue())));
        this.languages = Map.copyOf(loaded);
        this.cues = patterns(words.path("cues"));
        this.preferred = patterns(words.path("preferred"));
    }

    public String version() {
        return version;
    }

    /**
     * @param html тексты публикаций вакансии (HTML; разметка, закодированная сущностями, — как отдаёт Greenhouse —
     *             тоже); {@code null} или пусто — требований нет
     * @return требования по коду языка (ISO 639-1), по алфавиту кодов; пусто — не указаны
     */
    public List<Requirement> find(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Map<String, Boolean> required = new TreeMap<>();
        for (String part : parts(html)) {
            String text = PositionDictionary.normalize(part);
            if (!text.isBlank()) {
                partRequirements(text).forEach((language, mandatory) -> required.merge(language, mandatory,
                        Boolean::logicalOr));
            }
        }
        List<Requirement> found = new ArrayList<>();
        required.forEach((language, mandatory) -> found.add(new Requirement(language, mandatory)));
        return List.copyOf(found);
    }

    /** Языки одной части текста: код → обязателен. */
    private Map<String, Boolean> partRequirements(String text) {
        int[] starts = WORD.matcher(text).results().mapToInt(match -> match.start()).toArray();
        List<Span> cueSpans = spans(text, cues, starts);
        List<Span> markers = spans(text, preferred, starts);
        List<Mention> mentions = new ArrayList<>();
        languages.forEach((language, patterns) -> spans(text, patterns, starts).stream()
                .filter(span -> near(span, cueSpans, CUE_WINDOW) || near(span, markers, MARKER_CUE_WINDOW))
                .forEach(span -> mentions.add(new Mention(language, span))));
        boolean[] preferredMention = new boolean[mentions.size()];
        for (Span marker : markers) {
            int nearest = mentions.stream().mapToInt(mention -> distance(marker, mention.span())).min()
                    .orElse(Integer.MAX_VALUE);
            for (int index = 0; index < mentions.size(); index++) {
                if (nearest <= MARKER_WINDOW && distance(marker, mentions.get(index).span()) == nearest) {
                    preferredMention[index] = true;
                }
            }
        }
        Map<String, Boolean> required = new TreeMap<>();
        for (int index = 0; index < mentions.size(); index++) {
            required.merge(mentions.get(index).language(), !preferredMention[index], Boolean::logicalOr);
        }
        return required;
    }

    /**
     * Части текста: разметка снимается, после каждого блока — перенос строки; затем деление по переносам, маркерам
     * списка и концам предложений.
     */
    static List<String> parts(String html) {
        String decoded = Jsoup.parse(html).text();
        String markup = decoded.contains("<") ? decoded : html;
        Document document = Jsoup.parse(markup);
        document.select(BLOCKS).after("\n");
        return Arrays.stream(PART_END.split(document.wholeText())).filter(part -> !part.isBlank()).toList();
    }

    private static boolean near(Span span, List<Span> others, int window) {
        return others.stream().anyMatch(other -> distance(span, other) <= window);
    }

    /** Расстояние в словах: 0 — пересекаются, 1 — соседние слова. */
    private static int distance(Span first, Span second) {
        if (first.last() < second.first()) {
            return second.first() - first.last();
        }
        if (second.last() < first.first()) {
            return first.first() - second.last();
        }
        return 0;
    }

    private static List<Span> spans(String text, List<Pattern> patterns, int[] starts) {
        List<Span> spans = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                spans.add(new Span(word(starts, matcher.start()), word(starts, matcher.end() - 1)));
            }
        }
        return spans;
    }

    /** Номер слова, в котором стоит символ {@code offset}. */
    private static int word(int[] starts, int offset) {
        int found = Arrays.binarySearch(starts, offset);
        return found >= 0 ? found : -found - 2;
    }

    private static List<Pattern> patterns(JsonNode terms) {
        List<Pattern> patterns = new ArrayList<>();
        terms.forEach(term -> patterns.add(pattern(term.asText())));
        return List.copyOf(patterns);
    }

    /** Термин → шаблон по нормализованному тексту: слова через пробелы, {@code *} в конце слова — любое окончание. */
    private static Pattern pattern(String term) {
        List<String> words = new ArrayList<>();
        for (String word : PositionDictionary.normalize(term).strip().split("\\s+")) {
            words.add(word.endsWith(ANY_ENDING)
                    ? Pattern.quote(word.substring(0, word.length() - ANY_ENDING.length())) + "\\p{L}*"
                    : Pattern.quote(word));
        }
        return Pattern.compile(BEFORE + String.join("\\s+", words) + AFTER);
    }

    private static JsonNode readResource() {
        try (InputStream input = LanguageRequirements.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Language words " + RESOURCE + " are missing");
            }
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("Language words " + RESOURCE + " are not readable", exception);
        }
    }

    /**
     * Требование к языку.
     *
     * @param language код языка (ISO 639-1)
     * @param required {@code true} — обязателен, {@code false} — желателен
     */
    public record Requirement(String language, boolean required) {
    }

    /** Слова термина в части текста: номера первого и последнего. */
    private record Span(int first, int last) {
    }

    /** Название языка в части текста. */
    private record Mention(String language, Span span) {
    }
}
