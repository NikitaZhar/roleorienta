package com.roleorienta.core.domain;

/**
 * Уровень опыта (seniority) вакансии (§6, A08).
 *
 * <p>{@code JUNIOR}/{@code MEDIOR}/{@code SENIOR} — определён по явным словам в
 * описании; {@code UNKNOWN} — уровень из текста однозначно не следует (не упомянут
 * либо упомянуты сразу несколько уровней). Догадки не делаются (ADR-13): при
 * неоднозначности ставится {@code UNKNOWN}. Число лет опыта — отдельное поле
 * {@code experience_years_min} у {@link JobPosting} (A08: уровень и годы хранятся
 * раздельно).</p>
 */
public enum SeniorityLevel {
    JUNIOR,
    MEDIOR,
    SENIOR,
    UNKNOWN
}
