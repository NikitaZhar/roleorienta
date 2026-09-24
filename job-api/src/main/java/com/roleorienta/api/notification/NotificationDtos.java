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
     * @param change    что изменилось: публикация, компания, поле
     * @param readAt    момент прочтения или {@code null}
     * @param createdAt когда создано
     */
    public record NotificationResponse(Long id, Change change, Instant readAt, Instant createdAt) {

        static NotificationResponse of(Notification notification) {
            return new NotificationResponse(
                    notification.getId(),
                    new Change(notification.getJobPosting().getId(), notification.getCompanyId(),
                            notification.getFieldName()),
                    notification.getReadAt(),
                    notification.getCreatedAt());
        }
    }

    /**
     * Что изменилось (§78: поля уведомления сгруппированы).
     *
     * @param postingId публикация
     * @param companyId компания публикации
     * @param fieldName изменившееся поле
     */
    public record Change(Long postingId, Long companyId, String fieldName) {
    }
}
