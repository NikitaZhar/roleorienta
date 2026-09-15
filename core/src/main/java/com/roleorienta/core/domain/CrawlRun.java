package com.roleorienta.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Обход источника за одно окно планирования (миграция V5).
 *
 * <p>Планировщик создаёт по одному обходу на источник в каждом окне расписания;
 * пара {@code (source, windowStart)} уникальна — это durable-гарантия
 * идемпотентного планирования (ADR-12): повторный тик в том же окне не создаёт
 * второй обход. Область и полнота обхода (для семантики закрытия, A06)
 * наполняются логикой сбора в следующих инкрементах.</p>
 */
@Entity
@Table(
    name = "crawl_run",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_crawl_run_source_window",
        columnNames = {"source_id", "window_start"}
    )
)
public class CrawlRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    /** Начало окна расписания (UTC), к которому относится обход. */
    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrawlRunState state;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CrawlRun() {
    }

    /**
     * Создаёт обход для источника в заданном окне.
     *
     * @param source      источник сбора
     * @param windowStart начало окна расписания (UTC)
     * @param state       начальное состояние
     */
    public CrawlRun(Source source, Instant windowStart, CrawlRunState state) {
        this.source = source;
        this.windowStart = windowStart;
        this.state = state;
    }

    public Long getId() {
        return id;
    }

    public Source getSource() {
        return source;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public CrawlRunState getState() {
        return state;
    }

    public void setState(CrawlRunState state) {
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
