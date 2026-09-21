package com.roleorienta.api.subscription;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.core.domain.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Подписка пользователя на компанию (§4: «интерес пользователя к компании»; §7).
 *
 * <p>Одна строка на пару «пользователь × компания» (уникальный ключ
 * {@code (app_user_id, company_id)}, миграция V19) — durable-страховка идемпотентности:
 * повторная подписка не плодит строк (тот же приём, что у {@code SavedPosting} §30).</p>
 *
 * <p><b>Приватность (A23, §3.9).</b> Владелец берётся только из сессии и никогда не
 * приходит от клиента; данные одного пользователя недоступны другому. Связи заданы со
 * стороны «многих» через {@code @ManyToOne(fetch = LAZY, optional = false)}; значения —
 * через сеттеры (§3.10). Сущность живёт в {@code job-api} (пакет
 * {@code com.roleorienta.api.subscription}), {@code job-worker} её не использует; пакет
 * добавлен в {@code @EntityScan}. Компания — общая сущность {@code core}.</p>
 */
@Entity
@Table(name = "company_subscription",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_company_subscription_user_company",
                columnNames = {"app_user_id", "company_id"}))
public class CompanySubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Владелец подписки. Определяется по сессии, никогда не приходит от клиента (§3.9, A23). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser user;

    /** Компания, на которую подписан пользователь. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
