package com.roleorienta.worker.site;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * IČO в тексте страницы: подпись обязательна, пробелы в номере допустимы, короткий номер дополняется нулями;
 * DIČ и IČ DPH — не IČO.
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
     * Подписи «IČ», «I.Č.O.», «Company ID», «Reg. No.»; номер из 5–7 цифр — с ведущими нулями до восьми.
     */
    @Test
    void extractsOtherLabelsAndShortNumbers() {
        assertThat(IcoExtractor.extract("IČ: 35 757 442; I.Č.O. 31322832; Company ID: 684881; Reg. No. 1234567"))
                .containsExactly("35757442", "31322832", "00684881", "01234567");
    }

    /**
     * Без подписи, больше восьми или меньше пяти цифр, подпись внутри слова — не IČO.
     */
    @Test
    void ignoresUnlabelledAndMalformedNumbers() {
        assertThat(IcoExtractor.extract("Tel. 12345678, IČO: 1234, IČO: 123456789, Mexico: 12345678"))
                .isEmpty();
    }
}
