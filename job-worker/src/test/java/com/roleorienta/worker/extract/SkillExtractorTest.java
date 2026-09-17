package com.roleorienta.worker.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.roleorienta.core.domain.RequirementModality;
import com.roleorienta.core.domain.SkillStance;
import org.junit.jupiter.api.Test;

/**
 * Модульные тесты извлечения навыков (§6, A08). Проверяют без БД и Spring: сведение
 * алиасов к канону, границы токенов, обязательность по формулировкам и — раздельно —
 * отношение к навыку (запрос/отрицание/миграция).
 */
class SkillExtractorTest {

    private final SkillExtractor extractor = new SkillExtractor();

    @Test
    void aliasesCollapseToSingleCanonicalSkill() {
        assertThat(extractor.extract("We use PostgreSQL here. Postgres is everywhere."))
                .extracting(ExtractedSkill::skill)
                .containsExactly("PostgreSQL");
    }

    @Test
    void requiredCueGivesRequestedRequired() {
        assertThat(extractor.extract("Java is required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::stance, ExtractedSkill::modality)
                .containsExactly(tuple("Java", SkillStance.REQUESTED, RequirementModality.REQUIRED));
    }

    @Test
    void plusCueGivesPreferred() {
        assertThat(extractor.extract("Docker is a plus."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactly(tuple("Docker", RequirementModality.PREFERRED));
    }

    @Test
    void mentionWithoutCueGivesRequestedUnspecified() {
        assertThat(extractor.extract("We work with Kubernetes."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::stance, ExtractedSkill::modality)
                .containsExactly(tuple("Kubernetes", SkillStance.REQUESTED, RequirementModality.UNSPECIFIED));
    }

    @Test
    void negationGivesNegatedStance() {
        assertThat(extractor.extract("C# is not required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::stance, ExtractedSkill::modality)
                .containsExactly(tuple("C#", SkillStance.NEGATED, RequirementModality.UNSPECIFIED));
    }

    @Test
    void migrationGivesMigrationStance() {
        assertThat(extractor.extract("We are migrating away from AWS."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::stance, ExtractedSkill::modality)
                .containsExactly(tuple("AWS", SkillStance.MIGRATION, RequirementModality.UNSPECIFIED));
    }

    @Test
    void alternativeStaysRequestedWithoutRequiredForEither() {
        assertThat(extractor.extract("Java or Kotlin is required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::stance, ExtractedSkill::modality)
                .containsExactlyInAnyOrder(
                        tuple("Java", SkillStance.REQUESTED, RequirementModality.UNSPECIFIED),
                        tuple("Kotlin", SkillStance.REQUESTED, RequirementModality.UNSPECIFIED));
    }

    @Test
    void requestBeatsNegationAcrossSentences() {
        assertThat(extractor.extract("Java is required. Java is not required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::stance, ExtractedSkill::modality)
                .containsExactly(tuple("Java", SkillStance.REQUESTED, RequirementModality.REQUIRED));
    }

    @Test
    void tokenBoundaryDistinguishesJavaFromJavascript() {
        assertThat(extractor.extract("Strong JavaScript skills."))
                .extracting(ExtractedSkill::skill)
                .containsExactly("JavaScript");
    }

    @Test
    void specialCharacterTokensAreMatched() {
        assertThat(extractor.extract("Node.js and .NET and C++ are required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactlyInAnyOrder(
                        tuple("Node.js", RequirementModality.REQUIRED),
                        tuple(".NET", RequirementModality.REQUIRED),
                        tuple("C++", RequirementModality.REQUIRED));
    }

    @Test
    void strongestModalityWinsAcrossSentences() {
        assertThat(extractor.extract("Java is nice to have. Java is required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactly(tuple("Java", RequirementModality.REQUIRED));
    }

    @Test
    void curatedSetMatchesGolangButNotEnglishVerbGo() {
        assertThat(extractor.extract("Experience with Golang required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactly(tuple("Go", RequirementModality.REQUIRED));
        assertThat(extractor.extract("Please go to the office.")).isEmpty();
    }

    @Test
    void noSkillMentionedGivesEmpty() {
        assertThat(extractor.extract("We build great products.")).isEmpty();
    }

    @Test
    void blankOrNullDescriptionGivesEmpty() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   ")).isEmpty();
    }

    @Test
    void ruleVersionIsStable() {
        assertThat(SkillExtractor.VERSION).isEqualTo("skill-rules-2");
    }
}
