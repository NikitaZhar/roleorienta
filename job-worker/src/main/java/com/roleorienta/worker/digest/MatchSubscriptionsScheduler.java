package com.roleorienta.worker.digest;

import com.roleorienta.core.domain.Notification;
import com.roleorienta.core.domain.PendingChange;
import com.roleorienta.worker.collect.PendingChangeRepository;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Сопоставление изменений с подписками ({@code MATCH_SUBSCRIPTIONS}, §6, §39): по
 * записям журнала {@link PendingChange} формирует внутренние уведомления подписчикам.
 *
 * <p>Под leader-lock (сопоставляет одна реплика) забирает пачку необработанных записей,
 * для каждой находит компании публикации и их подписчиков (§37) и создаёт
 * {@link Notification} каждому, затем помечает запись обработанной ({@code processedAt}).
 * Всё — в транзакции leader-lock, поэтому запись и её уведомления фиксируются вместе.</p>
 *
 * <p>Пилотная семантика — at-least-once: при откате пачки записи остаются
 * необработанными и обрабатываются повторно (возможен повтор уведомления — дедуп/read
 * вынесены в следующие срезы, §39.x).</p>
 */
@Component
public class MatchSubscriptionsScheduler {

    /** Ключ advisory-лока роли сопоставления (отдельный от планировщика 1001 и гарвеста 1002). */
    static final long MATCH_LOCK_KEY = 1003L;

    private static final Logger log = LoggerFactory.getLogger(MatchSubscriptionsScheduler.class);

    private final PendingChangeRepository pendingChanges;
    private final SubscriptionLookup lookup;
    private final NotificationRepository notifications;
    private final PostgresLeaderLock leaderLock;
    private final int batchSize;

    public MatchSubscriptionsScheduler(
            PendingChangeRepository pendingChanges,
            SubscriptionLookup lookup,
            NotificationRepository notifications,
            PostgresLeaderLock leaderLock,
            @Value("${app.digest.match.batch-size:200}") int batchSize) {
        this.pendingChanges = pendingChanges;
        this.lookup = lookup;
        this.notifications = notifications;
        this.leaderLock = leaderLock;
        this.batchSize = batchSize;
    }

    /**
     * Один проход сопоставления под leader-lock.
     *
     * @return {@code true}, если эта реплика была лидером и выполнила проход
     */
    public boolean runOnce() {
        return leaderLock.runIfLeader(MATCH_LOCK_KEY, this::match);
    }

    /**
     * Обрабатывает пачку необработанных изменений. Выполняется в транзакции leader-lock.
     */
    void match() {
        List<PendingChange> batch =
                pendingChanges.findByProcessedAtIsNullOrderById(PageRequest.of(0, batchSize));
        int created = 0;
        for (PendingChange change : batch) {
            Long postingId = change.getJobPosting().getId();
            for (Long companyId : lookup.companyIdsForPosting(postingId)) {
                for (Long subscriberId : lookup.subscriberIdsForCompany(companyId)) {
                    notifications.save(new Notification(
                            subscriberId, change.getJobPosting(), companyId, change.getFieldName()));
                    created++;
                }
            }
            change.setProcessedAt(Instant.now());
            pendingChanges.save(change);
        }
        log.info("MATCH_SUBSCRIPTIONS: обработано изменений {}, создано уведомлений {}",
                batch.size(), created);
    }
}
