package com.roleorienta.worker.match;

import com.roleorienta.worker.match.PositionDictionary.Position;
import com.roleorienta.worker.match.PositionDictionary.Term;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Соответствие вакансии позициям словаря (бизнес-описание §4.4; технический документ §6, §16.13) —
 * детерминированно и с объяснением. Для каждой позиции по порядку:
 *
 * <ol>
 *   <li>исключающее слово в названии — позиция не подходит («QA Engineer (Java)» — не Java developer);</li>
 *   <li>эквивалентное название в названии вакансии — подходит («Java Engineer», «Java programátor»);</li>
 *   <li>роль и признак специализации в названии — подходит («Backend Engineer — Java»);</li>
 *   <li>роль в названии и признак в тексте не меньше {@link PositionDictionary#contentHits()} раз —
 *       подходит («Software Engineer» с Java в требованиях).</li>
 * </ol>
 *
 * <p>Общее сходство без признака специализации не даёт соответствия: вакансия другой специализации
 * не включается.</p>
 */
final class PositionMatcher {

    private PositionMatcher() {
    }

    /**
     * @param dictionary словарь позиций
     * @param title      название вакансии
     * @param text       текст вакансии без разметки; может быть пустым
     * @return подходящие позиции с объяснением
     */
    static List<Match> match(PositionDictionary dictionary, String title, String text) {
        String normalizedTitle = PositionDictionary.normalize(title);
        String normalizedText = PositionDictionary.normalize(text);
        List<Match> matches = new ArrayList<>();
        for (Position position : dictionary.positions()) {
            if (first(position.exclude(), normalizedTitle).isPresent()) {
                continue;
            }
            explain(position, normalizedTitle, normalizedText, dictionary.contentHits())
                    .ifPresent(explanation -> matches.add(new Match(position.code(), explanation)));
        }
        return matches;
    }

    private static Optional<String> explain(Position position, String title, String text, int contentHits) {
        Optional<Term> equivalent = first(position.titles(), title);
        if (equivalent.isPresent()) {
            return Optional.of("title: " + equivalent.get().text());
        }
        Optional<Term> role = first(position.specialization().roles(), title);
        if (role.isEmpty()) {
            return Optional.empty();
        }
        Optional<Term> keyword = first(position.specialization().keywords(), title);
        if (keyword.isPresent()) {
            return Optional.of("title: " + role.get().text() + " + " + keyword.get().text());
        }
        for (Term term : position.specialization().keywords()) {
            int hits = count(term, text);
            if (hits >= contentHits) {
                return Optional.of("title: " + role.get().text() + "; text: " + term.text() + " x" + hits);
            }
        }
        return Optional.empty();
    }

    private static Optional<Term> first(List<Term> terms, String text) {
        return terms.stream().filter(term -> term.pattern().matcher(text).find()).findFirst();
    }

    private static int count(Term term, String text) {
        Matcher matcher = term.pattern().matcher(text);
        int hits = 0;
        while (matcher.find()) {
            hits++;
        }
        return hits;
    }

    /**
     * @param code        код позиции
     * @param explanation какое название или признак сработали
     */
    record Match(String code, String explanation) {
    }
}
