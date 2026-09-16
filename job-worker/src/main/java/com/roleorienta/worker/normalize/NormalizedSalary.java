package com.roleorienta.worker.normalize;

import com.roleorienta.core.domain.SalaryBasis;
import com.roleorienta.core.domain.SalaryPeriod;
import java.math.BigDecimal;

/**
 * Результат нормализации зарплаты (§6, A09): суммы, валюта, период и база (gross/net).
 *
 * <p>{@link #ABSENT} — зарплата не указана вовсе (все поля {@code null}). Если сумма
 * есть, а период/база в источнике не указаны, они равны {@code UNKNOWN} — это явное
 * «неизвестно», отличимое от «зарплаты нет».</p>
 *
 * @param min      нижняя граница суммы или {@code null}
 * @param max      верхняя граница суммы или {@code null}
 * @param currency код валюты или {@code null}
 * @param period   период или {@code null} (если зарплаты нет)
 * @param basis    база (gross/net) или {@code null} (если зарплаты нет)
 */
public record NormalizedSalary(BigDecimal min, BigDecimal max, String currency,
                               SalaryPeriod period, SalaryBasis basis) {

    /** Зарплата не указана: все поля {@code null}. */
    public static final NormalizedSalary ABSENT = new NormalizedSalary(null, null, null, null, null);
}
