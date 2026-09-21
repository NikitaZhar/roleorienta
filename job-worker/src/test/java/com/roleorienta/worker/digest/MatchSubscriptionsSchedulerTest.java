package com.roleorienta.worker.digest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.Notification;
import com.roleorienta.core.domain.PendingChange;
import com.roleorienta.worker.collect.PendingChangeRepository;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Сопоставление изменений с подписками (§39): по журналу создаются уведомления
 * подписчикам, а запись помечается обработанной — и при наличии, и при отсутствии
 * подписчиков. Репозитории, lookup и leader-lock — заглушки (без сети и БД).
 */
class MatchSubscriptionsSchedulerTest {

    private final PendingChangeRepository pendingChanges = mock(PendingChangeRepository.class);
    private final SubscriptionLookup lookup = mock(SubscriptionLookup.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final PostgresLeaderLock leaderLock = mock(PostgresLeaderLock.class);
    private final MatchSubscriptionsScheduler scheduler =
            new MatchSubscriptionsScheduler(pendingChanges, lookup, notifications, leaderLock, 200);

    private final JobPosting posting = mock(JobPosting.class);
    private final PendingChange change = mock(PendingChange.class);

    @BeforeEach
    void setUp() {
        when(posting.getId()).thenReturn(1L);
        when(change.getJobPosting()).thenReturn(posting);
        when(change.getFieldName()).thenReturn("salary_min");
        when(pendingChanges.findByProcessedAtIsNullOrderById(any())).thenReturn(List.of(change));
    }

    @Test
    void notifiesEachSubscriberAndMarksProcessed() {
        when(lookup.companyIdsForPosting(1L)).thenReturn(List.of(10L));
        when(lookup.subscriberIdsForCompany(10L)).thenReturn(List.of(100L, 200L));

        scheduler.match();

        verify(notifications, times(2)).save(any(Notification.class));
        verify(change).setProcessedAt(any());
        verify(pendingChanges).save(change);
    }

    @Test
    void noSubscribersStillMarksProcessed() {
        when(lookup.companyIdsForPosting(1L)).thenReturn(List.of(10L));
        when(lookup.subscriberIdsForCompany(10L)).thenReturn(List.of());

        scheduler.match();

        verify(notifications, never()).save(any());
        verify(change).setProcessedAt(any());
        verify(pendingChanges).save(change);
    }
}
