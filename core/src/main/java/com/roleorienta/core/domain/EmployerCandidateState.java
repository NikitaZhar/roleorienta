package com.roleorienta.core.domain;

/**
 * Состояние кандидата в работодатели ({@link EmployerCandidate}) в очереди
 * подтверждения (§5, ADR-6).
 *
 * <p>{@code PENDING} — обнаружен, ждёт решения человека; {@code CONFIRMED} —
 * подтверждён, заведён {@code Source} и поставлен на сбор; {@code REJECTED} —
 * отклонён администратором.</p>
 */
public enum EmployerCandidateState {
    PENDING,
    CONFIRMED,
    REJECTED
}
