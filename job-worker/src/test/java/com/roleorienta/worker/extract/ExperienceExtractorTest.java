package com.roleorienta.worker.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.core.domain.SeniorityLevel;
import org.junit.jupiter.api.Test;

/**
 * Модульные тесты извлечения требования к опыту (§6, A08; §70 — уровень только из заголовка). Проверяют раздельность
 * «уровень / число лет», явный UNKNOWN при отсутствии или неоднозначности сигнала и
 * распознавание числа лет только по типовым конструкциям (без ложных срабатываний).
 */
class ExperienceExtractorTest {

    private final ExperienceExtractor extractor = new ExperienceExtractor();

    @Test
    void seniorLevelDetected() {
        assertThat(extractor.extract("Senior Java Engineer.", null).level())
                .isEqualTo(SeniorityLevel.SENIOR);
    }

    @Test
    void juniorLevelDetected() {
        assertThat(extractor.extract("Junior developer role.", null).level())
                .isEqualTo(SeniorityLevel.JUNIOR);
    }

    @Test
    void mediorDetectedFromMidLevelAndIntermediate() {
        assertThat(extractor.extract("This is a mid-level position.", null).level())
                .isEqualTo(SeniorityLevel.MEDIOR);
        assertThat(extractor.extract("Intermediate engineer.", null).level())
                .isEqualTo(SeniorityLevel.MEDIOR);
    }

    @Test
    void noLevelWordGivesUnknown() {
        assertThat(extractor.extract("Backend Engineer for our team.", null).level())
                .isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void conflictingLevelsGiveUnknown() {
        assertThat(extractor.extract("Junior or senior developers welcome.", null).level())
                .isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void seniorityWordDoesNotMatchLevel() {
        assertThat(extractor.extract("Seniority matters here.", null).level())
                .isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void plusYearsGivesMinimum() {
        assertThat(extractor.extract(null, "5+ years.").yearsMin()).isEqualTo(5);
    }

    @Test
    void yearsOfExperienceGivesMinimum() {
        assertThat(extractor.extract(null, "At least 3 years of experience required.").yearsMin())
                .isEqualTo(3);
    }

    @Test
    void rangeYearsGivesLowerBound() {
        assertThat(extractor.extract(null, "We need 3-5 years of experience.").yearsMin())
                .isEqualTo(3);
    }

    @Test
    void yearsWithoutExperienceContextIsIgnored() {
        assertThat(extractor.extract(null, "Founded 10 years ago.").yearsMin()).isNull();
    }

    @Test
    void levelAndYearsExtractedTogether() {
        ExtractedExperience experience =
                extractor.extract("Senior engineer", "Candidate with 5+ years of experience.");
        assertThat(experience.level()).isEqualTo(SeniorityLevel.SENIOR);
        assertThat(experience.yearsMin()).isEqualTo(5);
    }

    @Test
    void blankOrNullGivesUnknownAndNoYears() {
        assertThat(extractor.extract(null, null).level()).isEqualTo(SeniorityLevel.UNKNOWN);
        assertThat(extractor.extract(null, null).yearsMin()).isNull();
        assertThat(extractor.extract("   ", "   ").level()).isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void titleLevelWinsOverAmbiguousDescription() {
        // Реальный случай Workday (§69): уровень в заголовке, в описании — посторонние «senior/junior».
        ExtractedExperience experience = extractor.extract("Senior Technical Architect - Healthcare (m/w/d)",
                "You will advise senior management and mentor junior colleagues. 5+ years of experience.");
        assertThat(experience.level()).isEqualTo(SeniorityLevel.SENIOR);
        assertThat(experience.yearsMin()).isEqualTo(5);
    }

    @Test
    void descriptionIsNotUsedForLevel() {
        // §70: уровень в описании не используется — на стенде давал ложный SENIOR.
        assertThat(extractor.extract("Contracts Analyst 1",
                "You will report to senior management.").level()).isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void ambiguousTitleGivesUnknown() {
        assertThat(extractor.extract("Junior/Senior Java Engineer", "Senior role in our team.").level())
                .isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void titleLevelWithoutDescription() {
        ExtractedExperience experience = extractor.extract("Junior Electrical Designer", null);
        assertThat(experience.level()).isEqualTo(SeniorityLevel.JUNIOR);
        assertThat(experience.yearsMin()).isNull();
    }
}
