package com.roleorienta.worker.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.match.LanguageRequirements.Requirement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Требования к языку в тексте вакансии на настоящих словах {@code languages.json} (§158): признак знания рядом с
 * названием, «желателен» — к ближайшему языку, без признака «обязателен», части текста — блоки разметки и
 * предложения; названия стран и национальностей без признака знания не засчитываются.
 */
class LanguageRequirementsTests {

    private final LanguageRequirements languages = new LanguageRequirements();

    @Test
    void fluentLanguageIsRequired() {
        assertThat(languages.find("<p>Fluent English is required.</p>")).containsExactly(new Requirement("en", true));
    }

    @Test
    void plusMakesNearestLanguagePreferred() {
        assertThat(languages.find("<ul><li>Fluent English</li><li>German is a plus</li></ul>"))
                .containsExactly(new Requirement("de", false), new Requirement("en", true));
    }

    @Test
    void plusInOneSentenceGoesToNearestLanguage() {
        assertThat(languages.find("Excellent English skills, knowledge of German is an advantage."))
                .containsExactly(new Requirement("de", false), new Requirement("en", true));
    }

    @Test
    void slovakAndCzechPostings() {
        assertThat(languages.find("<p>Požadujeme: aktívna znalosť anglického jazyka slovom aj písmom.</p>"
                + "<p>Nemecký jazyk výhodou.</p>"))
                .containsExactly(new Requirement("de", false), new Requirement("en", true));
        assertThat(languages.find("Jazykové znalosti: Anglický jazyk - B2")).containsExactly(new Requirement("en", true));
        assertThat(languages.find("Znalost češtiny na úrovni rodilého mluvčího"))
                .containsExactly(new Requirement("cs", true));
    }

    @Test
    void germanAndHungarianPostings() {
        assertThat(languages.find("Sehr gute Englischkenntnisse; Deutsch von Vorteil"))
                .containsExactly(new Requirement("de", false), new Requirement("en", true));
        assertThat(languages.find("Angol nyelvtudás szükséges")).containsExactly(new Requirement("en", true));
    }

    @Test
    void countryOrNationalityWithoutCueIsNotLanguage() {
        assertThat(languages.find("<p>We are a German company based in the Slovak Republic.</p>"
                + "<p>Location: Bratislava, Slovakia</p>")).isEmpty();
    }

    @Test
    void requiredWinsOverPreferred() {
        assertThat(languages.find("<p>English is a plus.</p><p>Fluent English required.</p>"))
                .containsExactly(new Requirement("en", true));
    }

    @Test
    void encodedMarkupIsSplitIntoParts() {
        assertThat(languages.find("&lt;ul&gt;&lt;li&gt;Fluent English&lt;/li&gt;&lt;li&gt;Polish is a plus&lt;/li&gt;"
                + "&lt;/ul&gt;")).containsExactly(new Requirement("en", true), new Requirement("pl", false));
    }

    @Test
    void noTextNoRequirements() {
        assertThat(languages.find(null)).isEmpty();
        assertThat(languages.find("")).isEqualTo(List.of());
        assertThat(languages.find("<p>Java developer, Spring Boot, PostgreSQL.</p>")).isEmpty();
    }
}
