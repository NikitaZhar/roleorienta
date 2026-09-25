package com.roleorienta.worker.digest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.worker.digest.DigestComposer.Message;
import com.roleorienta.worker.digest.DigestContent.ChangedPosting;
import com.roleorienta.worker.digest.DigestContent.NewPosting;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Текст дайджеста (A7, §88): разделы, пометка покрытия, поля словами, «и ещё N». */
class DigestComposerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    @Test
    void composesSectionsWithCoverageAndFieldLabels() {
        DigestContent content = new DigestContent(
                List.of(new NewPosting("Java Developer", "https://jobs.example/1", "Accenture", CoverageState.SITE_ONLY),
                        new NewPosting("Tester", "https://jobs.example/2", "Accenture", CoverageState.UNKNOWN)),
                List.of(new ChangedPosting("Analyst", "https://jobs.example/3", "Hitachi",
                        List.of("salary_min", "salary_max", "city"))));

        Message message = DigestComposer.compose(content, DAY, 30);

        assertEquals("roleorienta — дайджест за 25.09.2026: новых вакансий 2, изменений 1", message.subject());
        assertTrue(message.text().contains("• Accenture: Java Developer [только на сайте компании]\n"
                + "  https://jobs.example/1\n"), message.text());
        assertTrue(message.text().contains("• Accenture: Tester\n"), message.text());
        assertTrue(message.text().contains("• Hitachi: Analyst — изменено: зарплата, место\n"), message.text());
    }

    @Test
    void longSectionIsCutWithRemainderAndEmptySectionIsOmitted() {
        List<NewPosting> many = List.of(
                new NewPosting("A1", "u1", "Acme", CoverageState.UNKNOWN),
                new NewPosting("A2", "u2", "Acme", CoverageState.UNKNOWN),
                new NewPosting("A3", "u3", "Acme", CoverageState.UNKNOWN));

        String text = DigestComposer.compose(new DigestContent(many, List.of()), DAY, 2).text();

        assertTrue(text.contains("… и ещё 1\n"), text);
        assertFalse(text.contains("A3"), text);
        assertFalse(text.contains("Изменения"), text);
    }

    @Test
    void unknownFieldIsShownAsIs() {
        assertEquals("title, уровень", DigestComposer.labels(List.of("seniority", "title")));
    }
}
