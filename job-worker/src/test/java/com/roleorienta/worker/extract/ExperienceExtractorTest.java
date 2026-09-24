package com.roleorienta.worker.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.core.domain.SeniorityLevel;
import org.junit.jupiter.api.Test;

/**
 * Модульные тесты извлечения требования к опыту (§6, A08). Проверяют раздельность
 * «уровень / число лет», явный UNKNOWN при отсутствии или неоднозначности сигнала и
 * распознавание числа лет только по типовым конструкциям (без ложных срабатываний).
 */
class ExperienceExtractorTest {

    private final ExperienceExtractor extractor = new ExperienceExtractor();

    @Test
    void seniorLevelDetected() {
        assertThat(extractor.extract("Senior Java Engineer.").level())
                .isEqualTo(SeniorityLevel.SENIOR);
    }

    @Test
    void juniorLevelDetected() {
        assertThat(extractor.extract("Junior developer role.").level())
                .isEqualTo(SeniorityLevel.JUNIOR);
    }

    @Test
    void mediorDetectedFromMidLevelAndIntermediate() {
        assertThat(extractor.extract("This is a mid-level position.").level())
                .isEqualTo(SeniorityLevel.MEDIOR);
        assertThat(extractor.extract("Intermediate engineer.").level())
                .isEqualTo(SeniorityLevel.MEDIOR);
    }

    @Test
    void noLevelWordGivesUnknown() {
        assertThat(extractor.extract("Backend Engineer for our team.").level())
                .isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void conflictingLevelsGiveUnknown() {
        assertThat(extractor.extract("Junior or senior developers welcome.").level())
                .isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void seniorityWordDoesNotMatchLevel() {
        assertThat(extractor.extract("Seniority matters here.").level())
                .isEqualTo(SeniorityLevel.UNKNOWN);
    }

    @Test
    void plusYearsGivesMinimum() {
        assertThat(extractor.extract("5+ years.").yearsMin()).isEqualTo(5);
    }

    @Test
    void yearsOfExperienceGivesMinimum() {
        assertThat(extractor.extract("At least 3 years of experience required.").yearsMin())
                .isEqualTo(3);
    }

    @Test
    void rangeYearsGivesLowerBound() {
        assertThat(extractor.extract("We need 3-5 years of experience.").yearsMin())
                .isEqualTo(3);
    }

    @Test
    void yearsWithoutExperienceContextIsIgnored() {
        assertThat(extractor.extract("Founded 10 years ago.").yearsMin()).isNull();
    }

    @Test
    void levelAndYearsExtractedTogether() {
        ExtractedExperience experience =
                extractor.extract("Senior engineer with 5+ years of experience.");
        assertThat(experience.level()).isEqualTo(SeniorityLevel.SENIOR);
        assertThat(experience.yearsMin()).isEqualTo(5);
    }

    @Test
    void blankOrNullGivesUnknownAndNoYears() {
        assertThat(extractor.extract(null).level()).isEqualTo(SeniorityLevel.UNKNOWN);
        assertThat(extractor.extract(null).yearsMin()).isNull();
        assertThat(extractor.extract("   ").level()).isEqualTo(SeniorityLevel.UNKNOWN);
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
    void descriptionUsedWhenTitleHasNoLevel() {
        assertThat(extractor.extract("Software Developer - Cybersecurity (f/m/d)",
                "We are looking for a junior developer.").level()).isEqualTo(SeniorityLevel.JUNIOR);
    }

    @Test
    void ambiguousTitleFallsBackToDescription() {
        assertThat(extractor.extract("Junior/Senior Java Engineer", "Senior role in our team.").level())
                .isEqualTo(SeniorityLevel.SENIOR);
    }

    @Test
    void titleLevelWithoutDescription() {
        ExtractedExperience experience = extractor.extract("Junior Electrical Designer", null);
        assertThat(experience.level()).isEqualTo(SeniorityLevel.JUNIOR);
        assertThat(experience.yearsMin()).isNull();
    }
}
