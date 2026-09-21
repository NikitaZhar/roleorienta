package com.roleorienta.api.application;

/**
 * Состояние отклика на вакансию (§7). Стартовое — {@link #APPLIED}; переходы между
 * состояниями (с проверкой допустимости и {@code If-Match}) — следующий срез.
 */
public enum ApplicationStatus {
    APPLIED,
    INTERVIEWING,
    OFFER,
    REJECTED,
    WITHDRAWN
}
