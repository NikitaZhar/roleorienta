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
 * период/база неизвестны».
 */
class SalaryNormalizerTest {

    private final SalaryNormalizer normalizer = new SalaryNormalizer();

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
}
