package com.roleorienta.api.saved;

import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * DTO модуля персональных маркеров (§7): только данные, без логики (§3.3). Вложенные
 * записи — чтобы не плодить файлы под каждый мелкий тип (§3.10).
 */
public final class SavedPostingDtos {

    private SavedPostingDtos() {
    }

    /**
     * Запрос скрытия публикации: необязательная причина.
     *
     * @param reason причина скрытия (необязательна; ограничена по длине)
     */
    public record HideRequest(@Size(max = 500) String reason) {
    }

    /**
     * Ответ-представление маркера.
     *
     * @param postingId    id публикации
     * @param state        состояние (сохранена/скрыта) или {@code null}
     * @param hiddenReason причина скрытия или {@code null}
     * @param seenAt       момент первого просмотра или {@code null}
     * @param createdAt    момент первого создания маркера
     */
    public record SavedPostingResponse(Long postingId, SavedState state, String hiddenReason,
                                       Instant seenAt, Instant createdAt) {
    }
}
