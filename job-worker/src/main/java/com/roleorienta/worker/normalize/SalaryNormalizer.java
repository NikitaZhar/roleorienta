package com.roleorienta.worker.normalize;

import com.roleorienta.core.domain.SalaryBasis;
import com.roleorienta.core.domain.SalaryPeriod;
import com.roleorienta.worker.adapters.CompensationRange;
import org.springframework.stereotype.Component;

/**
 * Нормализатор зарплаты (§6, A09): приводит структурированный диапазон источника к
 * {@link NormalizedSalary}, соблюдая правило «валюта, период и база не смешиваются,
 * неизвестное — явно».
 *
 * <p>Из зарплатного диапазона Greenhouse ({@code pay_input_ranges}) известны только
 * суммы и валюта; период и база там не сообщаются, поэтому ставятся {@code UNKNOWN}
 * (не угадываются). Если диапазона нет — {@link NormalizedSalary#ABSENT}. Логика
 * вынесена в отдельный компонент, чтобы правила нормализации были в одном месте и
 * покрывались модульными тестами независимо от обработчика.</p>
 */
@Component
public class SalaryNormalizer {

    /**
     * @param range структурированный зарплатный диапазон из адаптера или {@code null}
     * @return нормализованная зарплата ({@link NormalizedSalary#ABSENT}, если суммы нет)
     */
    public NormalizedSalary normalize(CompensationRange range) {
        if (range == null || range.isEmpty()) {
            return NormalizedSalary.ABSENT;
        }
        return new NormalizedSalary(
                range.minAmount(),
                range.maxAmount(),
                range.currency(),
                SalaryPeriod.UNKNOWN,
                SalaryBasis.UNKNOWN);
    }
}
