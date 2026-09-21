package com.roleorienta.worker.digest;

import com.roleorienta.core.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

/** Запись уведомлений сопоставлением MATCH_SUBSCRIPTIONS (§39). */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
