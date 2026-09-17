package com.roleorienta.worker.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.roleorienta.core.domain.LanguageMention;
import com.roleorienta.core.domain.LanguageModality;
import org.junit.jupiter.api.Test;

/**
 * Модульные тесты извлечения языковых требований (§6, A07). Проверяют раздельность
 * «факт упоминания / обязательность» и правила модальности без БД и Spring.
 */
class LanguageExtractorTest {

    private final LanguageExtractor extractor = new LanguageExtractor();

    @Test
    void requiredAndPreferredAreDistinguished() {
        assertThat(extractor.extract("Fluent English is required. German is a plus."))
                .extracting(ExtractedLanguage::languageCode, ExtractedLanguage::mentioned,
                        ExtractedLanguage::modality)
                .containsExactlyInAnyOrder(
                        tuple("en", LanguageMention.YES, LanguageModality.REQUIRED),
                        tuple("de", LanguageMention.YES, LanguageModality.PREFERRED));
    }

    @Test
    void mereMentionGivesUnspecifiedNotRequired() {
        assertThat(extractor.extract("You will join a German-speaking team."))
                .extracting(ExtractedLanguage::languageCode, ExtractedLanguage::mentioned,
                        ExtractedLanguage::modality)
                .containsExactly(tuple("de", LanguageMention.YES, LanguageModality.UNSPECIFIED));
    }

    @Test
    void negationDoesNotGiveRequired() {
        assertThat(extractor.extract("German is not required."))
                .extracting(ExtractedLanguage::languageCode, ExtractedLanguage::modality)
                .containsExactly(tuple("de", LanguageModality.UNSPECIFIED));
    }

    @Test
    void noLanguageMentionedGivesEmpty() {
        assertThat(extractor.extract("We build great products.")).isEmpty();
    }

    @Test
    void blankOrNullDescriptionGivesEmpty() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   ")).isEmpty();
    }

    @Test
    void ruleVersionIsStable() {
        assertThat(LanguageExtractor.VERSION).isEqualTo("lang-rules-1");
    }
}
