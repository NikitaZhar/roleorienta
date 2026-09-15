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
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Единица фоновой работы в рамках обхода (миграция V5).
 *
 * <p>Принадлежит одному {@link CrawlRun}. Несёт тип задания, состояние и число
 * попыток. Планировщик создаёт задание вместе с обходом и outbox-событием в одной
 * транзакции; далее задание доставляется через магистраль (§6, §8 техдока).
 * Контрольная точка и версия обработки добавляются вместе с логикой сбора.</p>
 */
@Entity
@Table(name = "crawl_task")
public class CrawlTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crawl_run_id", nullable = false)
    private CrawlRun crawlRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrawlTaskType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrawlTaskState state;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CrawlTask() {
    }

    /**
     * Создаёт задание в рамках обхода.
     *
     * @param crawlRun обход, к которому относится задание
     * @param type     тип задания
     * @param state    начальное состояние
     */
    public CrawlTask(CrawlRun crawlRun, CrawlTaskType type, CrawlTaskState state) {
        this.crawlRun = crawlRun;
        this.type = type;
        this.state = state;
    }

    public Long getId() {
        return id;
    }

    public CrawlRun getCrawlRun() {
        return crawlRun;
    }

    public CrawlTaskType getType() {
        return type;
    }

    public CrawlTaskState getState() {
        return state;
    }

    public void setState(CrawlTaskState state) {
        this.state = state;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
