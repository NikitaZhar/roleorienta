package com.roleorienta.worker.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.core.domain.SalaryBasis;
import com.roleorienta.core.domain.SalaryPeriod;
import com.roleorienta.worker.adapters.CompensationRange;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Модульные тесты нормализации зарплаты (§6, A09). Проверяют правило «валюта известна,
 * период и база — явное UNKNOWN» и отличие «зарплаты нет» ({@code ABSENT}) от «есть, но
 * период/база неизвестны», а также выбор источника в {@code resolve} (§66): структурный
 * диапазон главнее текста, без него — сумма из текста с фрагментом как сырым значением.
 */
class SalaryNormalizerTest {

    private final SalaryNormalizer normalizer = new SalaryNormalizer(new SalaryTextExtractor());

    @Test
    void amountsAndCurrencyKeptPeriodAndBasisUnknown() {
        NormalizedSalary salary = normalizer.normalize(
                new CompensationRange(new BigDecimal("75000"), new BigDecimal("110000"), "EUR"));
        assertThat(salary.min()).isEqualByComparingTo("75000");
        assertThat(salary.max()).isEqualByComparingTo("110000");
        assertThat(salary.currency()).isEqualTo("EUR");
        assertThat(salary.period()).isEqualTo(SalaryPeriod.UNKNOWN);
        assertThat(salary.basis()).isEqualTo(SalaryBasis.UNKNOWN);
    }

    @Test
    void nullRangeIsAbsent() {
        assertThat(normalizer.normalize(null)).isEqualTo(NormalizedSalary.ABSENT);
    }

    @Test
    void rangeWithoutAmountsIsAbsentEvenWithCurrency() {
        assertThat(normalizer.normalize(new CompensationRange(null, null, "EUR")))
                .isEqualTo(NormalizedSalary.ABSENT);
    }

    @Test
    void structuredRangeWinsOverText() {
        ExtractedSalary resolved = normalizer.resolve(
                new CompensationRange(new BigDecimal("75000"), new BigDecimal("110000"), "EUR"),
                "75000-110000 EUR", "The salary for this position starts at € 50.000 gross p.a.");
        assertThat(resolved.salary().min()).isEqualByComparingTo("75000");
        assertThat(resolved.salary().period()).isEqualTo(SalaryPeriod.UNKNOWN);
        assertThat(resolved.fragment()).isEqualTo("75000-110000 EUR");
    }

    @Test
    void withoutStructuredRangeSalaryComesFromText() {
        ExtractedSalary resolved = normalizer.resolve(null, null,
                "WE OFFER The salary for this position starts at € 60.000 gross p.a. Actual compensation");
        assertThat(resolved.salary().min()).isEqualByComparingTo("60000");
        assertThat(resolved.salary().max()).isNull();
        assertThat(resolved.salary().period()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(resolved.salary().basis()).isEqualTo(SalaryBasis.GROSS);
        assertThat(resolved.fragment()).contains("€ 60.000 gross p.a.");
    }

    @Test
    void noSalaryAnywhereIsAbsent() {
        ExtractedSalary resolved = normalizer.resolve(null, null, "Competitive compensation packages.");
        assertThat(resolved.salary()).isEqualTo(NormalizedSalary.ABSENT);
        assertThat(resolved.fragment()).isNull();
    }
}
