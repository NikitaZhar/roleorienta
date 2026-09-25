package com.roleorienta.worker.digest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Одно письмо-дайджест одному пользователю за окно {@code (windowStart, windowEnd]} (A7, §88, V31).
 * Текст не хранится: при каждой попытке он собирается заново из данных окна.
 */
@Entity
@Table(name = "digest_delivery")
public class DigestDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_user_id", nullable = false, updatable = false)
    private Long appUserId;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DigestDeliveryState state;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DigestDelivery() {
    }

    /**
     * Новое письмо в состоянии {@link DigestDeliveryState#PENDING}.
     *
     * @param appUserId   получатель
     * @param windowStart начало окна (не включая)
     * @param windowEnd   конец окна (включая)
     */
    public DigestDelivery(Long appUserId, Instant windowStart, Instant windowEnd) {
        this.appUserId = appUserId;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.state = DigestDeliveryState.PENDING;
    }

    /**
     * Письмо принято SMTP-сервером.
     *
     * @param now момент отправки
     */
    public void markSent(Instant now) {
        attempts++;
        state = DigestDeliveryState.SENT;
        sentAt = now;
        lastError = null;
    }

    /**
     * Неудачная попытка: после {@code maxAttempts} попыток письмо больше не отправляется.
     *
     * @param error       причина (без секретов: сообщение исключения почтового клиента)
     * @param maxAttempts предел попыток
     */
    public void markFailedAttempt(String error, int maxAttempts) {
        attempts++;
        lastError = error;
        state = attempts >= maxAttempts ? DigestDeliveryState.FAILED : DigestDeliveryState.PENDING;
    }

    /**
     * Письмо не отправляется и не повторяется (некому или нечего отправлять).
     *
     * @param reason причина
     */
    public void markFailed(String reason) {
        lastError = reason;
        state = DigestDeliveryState.FAILED;
    }

    public Long getId() { return id; }

    public Long getAppUserId() { return appUserId; }

    public Instant getWindowStart() { return windowStart; }

    public Instant getWindowEnd() { return windowEnd; }

    public DigestDeliveryState getState() { return state; }

    public int getAttempts() { return attempts; }

    public String getLastError() { return lastError; }

    public Instant getSentAt() { return sentAt; }
}
