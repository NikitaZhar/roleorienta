package com.roleorienta.api.notification;

import com.roleorienta.core.domain.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Чтение уведомлений — всегда в пределах владельца (A23). */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByAppUserIdOrderByIdDesc(Long appUserId);
}
