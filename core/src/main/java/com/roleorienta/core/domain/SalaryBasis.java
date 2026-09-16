package com.roleorienta.core.domain;

/**
 * База суммы зарплаты: до вычета налогов (gross) или после (net) — §6, A09.
 *
 * <p>{@code UNKNOWN} — база в источнике не указана. gross и net не смешиваются и не
 * угадываются: если источник не сказал, значение остаётся {@code UNKNOWN}.</p>
 */
public enum SalaryBasis {
    GROSS,
    NET,
    UNKNOWN
}
