package com.roleorienta.worker.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Соответствие позициям по словарю приложения ({@code positions.json}): эквивалентные названия,
 * роль и признак в названии, признак в тексте, исключения, границы слов, диакритика.
 */
class PositionMatcherTests {

    private final PositionDictionary dictionary = new PositionDictionary();

    /**
     * Роль и специализация в названии: «Backend Engineer — Java» — Java developer (бизнес-описание §4.4).
     */
    @Test
    void matchesRoleAndSpecializationInTitle() {
        List<PositionMatcher.Match> matches = PositionMatcher.match(dictionary, "Backend Engineer — Java", "");

        assertThat(matches).extracting(PositionMatcher.Match::code).containsExactly("java-developer");
        assertThat(matches.get(0).explanation()).isEqualTo("title: engineer + java");
    }

    /**
     * Границы слов: JavaScript — не Java; C# и .NET; диакритика не мешает.
     */
    @Test
    void respectsWordBoundariesAndDiacritics() {
        assertThat(codes("JavaScript Developer", "")).containsExactly("frontend-developer");
        assertThat(codes("Programátor C#/.NET", "")).containsExactly("dotnet-developer");
        assertThat(codes("Účtovník / účtovníčka", "")).containsExactly("accountant");
    }

    /**
     * Исключающее слово: QA-вакансия с Java — не Java developer, а QA.
     */
    @Test
    void excludesOtherSpecialization() {
        assertThat(codes("QA Engineer (Java)", "")).containsExactly("qa-engineer");
    }

    /**
     * Роль в названии и признак в тексте не меньше трёх раз; реже — не подходит; без роли — не
     * подходит (общее сходство не включает вакансию).
     */
    @Test
    void usesContentOnlyWithRoleInTitle() {
        assertThat(codes("Software Engineer", "We use Java 21, Spring Boot and Java tooling. Java is our core."))
                .containsExactly("java-developer");
        assertThat(codes("Software Engineer", "Some Java.")).isEmpty();
        assertThat(codes("Sales Assistant", "Java Java Java")).isEmpty();
    }

    private List<String> codes(String title, String text) {
        return PositionMatcher.match(dictionary, title, text).stream().map(PositionMatcher.Match::code).toList();
    }
}
