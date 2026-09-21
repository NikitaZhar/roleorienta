package com.roleorienta.api.notification;

import com.roleorienta.core.domain.Notification;
import java.time.Instant;

/** Ответы эндпоинта уведомлений (§7). */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * Представление уведомления.
     *
     * @param id        id уведомления
     * @param postingId публикация, в которой произошло изменение
     * @param companyId компания подписки
     * @param fieldName изменившееся поле
     * @param readAt    момент прочтения или {@code null}
     * @param createdAt когда создано
     */
    public record NotificationResponse(Long id, Long postingId, Long companyId,
                                       String fieldName, Instant readAt, Instant createdAt) {

        static NotificationResponse of(Notification notification) {
            return new NotificationResponse(
                    notification.getId(),
                    notification.getJobPosting().getId(),
                    notification.getCompanyId(),
                    notification.getFieldName(),
                    notification.getReadAt(),
                    notification.getCreatedAt());
        }
    }
}
