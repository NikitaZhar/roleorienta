package com.roleorienta.worker.site;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * IČO в тексте страницы: подпись обязательна, пробелы в номере допустимы, DIČ и IČ DPH — не IČO.
 */
class IcoExtractorTests {

    /**
     * Обычные записи на словацких сайтах.
     */
    @Test
    void extractsLabelledNumbers() {
        assertThat(IcoExtractor.extract("Alfa s.r.o., IČO: 11 111 111, DIČ: 2020202020, IČ DPH: SK2020202020"))
                .containsExactly("11111111");
        assertThat(IcoExtractor.extract("ičo 44618077 | ICO:12345678")).containsExactly("44618077", "12345678");
    }

    /**
     * Без подписи, не восемь цифр, подпись внутри слова — не IČO.
     */
    @Test
    void ignoresUnlabelledAndMalformedNumbers() {
        assertThat(IcoExtractor.extract("Tel. 12345678, IČO: 1234567, IČO: 123456789, Mexico: 12345678"))
                .isEmpty();
    }
}
