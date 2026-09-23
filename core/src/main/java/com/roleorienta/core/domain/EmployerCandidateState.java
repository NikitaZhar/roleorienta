package com.roleorienta.core.domain;

/**
 * Состояние кандидата в работодатели ({@link EmployerCandidate}) в очереди
 * подтверждения (§5, ADR-6).
 *
 * <p>{@code PENDING} — обнаружен, ждёт решения человека; {@code CONFIRMED} —
 * подтверждён, заведён {@code Source} и поставлен на сбор; {@code REJECTED} —
 * отклонён администратором; {@code OUT_OF_MARKET} — лента валидна, но у работодателя нет
 * публикаций на целевом рынке пилота (по распределению стран от провайдера, §56) —
 * отсеян автоматически, в очередь подтверждения не попадает и повторно не проверяется
 * (пересмотр — при смене рынка).</p>
 */
public enum EmployerCandidateState {
    PENDING,
    CONFIRMED,
    REJECTED,
    OUT_OF_MARKET
}
