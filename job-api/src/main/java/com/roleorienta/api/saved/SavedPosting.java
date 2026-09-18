package com.roleorienta.api.saved;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.core.domain.JobPosting;
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
 * Персональный маркер публикации (§7): отношение конкретного пользователя к конкретной
 * публикации — сохранена, скрыта (с причиной) и/или просмотрена.
 *
 * <p>Одна строка на пару «пользователь × публикация» (уникальный ключ
 * {@code (app_user_id, job_posting_id)}, миграция V16) — durable-страховка идемпотентности:
 * повторные пометки не плодят строк (тот же приём, что {@code uq_crawl_run} §14 и
 * {@code uq_app_user_email_lower} §29).</p>
 *
 * <p><b>Модель после §31.</b> «Сохранить»/«скрыть» и «просмотрено» независимы: {@code state}
 * ({@link SavedState}: {@code SAVED}|{@code HIDDEN}) допускает {@code null} — «явного
 * сохранения/скрытия нет», а {@code seenAt} отмечает факт просмотра ортогонально. Строка
 * осмысленна, пока задан хотя бы один из них; когда оба пусты, сервис удаляет строку.</p>
 *
 * <p>Связи заданы со стороны «многих» через {@code @ManyToOne(fetch = LAZY,
 * optional = false)} (§11.3 техдока): связанные строки подгружаются лениво, коллекций на
 * другой стороне нет. Значения заполняются через сеттеры, как у других сущностей проекта
 * (например {@code AppUser}), а не конструктором со многими параметрами (§3.10).</p>
 *
 * <p>Сущность живёт в модуле {@code job-api} (пакет {@code com.roleorienta.api.saved}), а
 * не в общем {@code core}: маркерами управляет только API, {@code job-worker} их не
 * использует (та же логика размещения, что у {@code AppUser} §29). Пакет добавлен в
 * {@code @EntityScan} приложения.</p>
 */
@Entity
@Table(name = "saved_posting",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_saved_posting_user_posting",
                columnNames = {"app_user_id", "job_posting_id"}))
public class SavedPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Владелец маркера. Определяется по сессии, никогда не приходит от клиента (§3.9, A23). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser user;

    /** Публикация, к которой относится маркер. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting posting;

    /** Сохранена/скрыта или {@code null} — явного сохранения/скрытия нет (только просмотр). */
    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private SavedState state;

    /** Причина скрытия; осмысленна только при {@link SavedState#HIDDEN}, иначе {@code null}. */
    @Column(name = "hidden_reason")
    private String hiddenReason;

    /** Момент первого просмотра публикации пользователем или {@code null}. */
    @Column(name = "seen_at")
    private Instant seenAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public JobPosting getPosting() { return posting; }
    public void setPosting(JobPosting posting) { this.posting = posting; }

    public SavedState getState() { return state; }
    public void setState(SavedState state) { this.state = state; }

    public String getHiddenReason() { return hiddenReason; }
    public void setHiddenReason(String hiddenReason) { this.hiddenReason = hiddenReason; }

    public Instant getSeenAt() { return seenAt; }
    public void setSeenAt(Instant seenAt) { this.seenAt = seenAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
