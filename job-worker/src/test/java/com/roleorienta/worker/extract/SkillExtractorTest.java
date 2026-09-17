package com.roleorienta.worker.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.roleorienta.core.domain.RequirementModality;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Модульные тесты извлечения навыков (§6, A08). Проверяют правила без БД и Spring:
 * сведение алиасов к канону, обязательность по формулировкам, консервативную обработку
 * альтернатив и отрицаний, границы технических токенов и осторожность набора.
 */
class SkillExtractorTest {

    private final SkillExtractor extractor = new SkillExtractor();

    @Test
    void aliasesCollapseToSingleCanonicalSkill() {
        List<ExtractedSkill> skills = extractor.extract("We use PostgreSQL here. Postgres is everywhere.");
        assertThat(skills)
                .extracting(ExtractedSkill::skill)
                .containsExactly("PostgreSQL");
    }

    @Test
    void requiredCueGivesRequired() {
        assertThat(extractor.extract("Java is required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactly(tuple("Java", RequirementModality.REQUIRED));
    }

    @Test
    void plusCueGivesPreferred() {
        assertThat(extractor.extract("Docker is a plus."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactly(tuple("Docker", RequirementModality.PREFERRED));
    }

    @Test
    void mentionWithoutCueGivesUnspecified() {
        assertThat(extractor.extract("We work with Kubernetes."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactly(tuple("Kubernetes", RequirementModality.UNSPECIFIED));
    }

    @Test
    void negationDoesNotGiveRequired() {
        assertThat(extractor.extract("C# is not required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactly(tuple("C#", RequirementModality.UNSPECIFIED));
    }

    @Test
    void alternativeDoesNotGiveRequiredForEither() {
        assertThat(extractor.extract("Java or Kotlin is required."))
                .extracting(ExtractedSkill::skill, ExtractedSkill::modality)
                .containsExactlyInAnyOrder(
                        tuple("Java", RequirementModality.UNSPECIFIED),
                        tuple("Kotlin", RequirementModality.UNSPECIFIED));
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
        assertThat(SkillExtractor.VERSION).isEqualTo("skill-rules-1");
    }
}
