package com.roleorienta.core.domain;

/**
 * Период, к которому относится зарплата (§6, A09).
 *
 * <p>{@code UNKNOWN} — период в источнике не указан. Он не смешивается с суммой:
 * «80000» без периода — это явное «неизвестно», а не «в год». Догадки не делаются.</p>
 */
public enum SalaryPeriod {
    YEAR,
    MONTH,
    WEEK,
    DAY,
    HOUR,
    UNKNOWN
}
