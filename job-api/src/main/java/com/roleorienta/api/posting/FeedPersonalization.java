package com.roleorienta.api.posting;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.saved.SavedPosting;
import com.roleorienta.api.saved.SavedPostingRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Персонализация ленты публикаций под вошедшего пользователя (§31): резолв владельца из
 * сессии и его маркеры на публикациях страницы.
 *
 * <p>Выделено в отдельный компонент, чтобы {@link PostingQueryService} не выходил за лимит
 * зависимостей (§3.10) и не смешивал чтение публикаций с логикой персонализации (§3.3,
 * единственная ответственность). Это единственная точка связи read-слоя ленты с модулем
 * маркеров ({@code com.roleorienta.api.saved}).</p>
 *
 * <p><b>Аноним (§3.9/§7).</b> Для неаутентифицированного запроса {@link #currentUserId}
 * возвращает {@code null}: лента остаётся публичной и неперсонализированной, контракт
 * анонимного чтения не меняется. Идентификатор пользователя берётся только из сессии,
 * никогда из запроса (A23).</p>
 */
@Component
public class FeedPersonalization {

    private final AppUserRepository users;
    private final SavedPostingRepository markers;

    /**
     * @param users   резолв пользователя по email из сессии
     * @param markers маркеры пользователя на публикациях
     */
    public FeedPersonalization(AppUserRepository users, SavedPostingRepository markers) {
        this.users = users;
        this.markers = markers;
    }

    /**
     * Идентификатор текущего пользователя или {@code null}, если запрос анонимный
     * (аутентификации нет, либо пользователь по email из сессии не найден — например
     * анонимный токен).
     *
     * @param authentication текущая аутентификация или {@code null}
     * @return id владельца или {@code null}
     */
    public Long currentUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        return users.findByEmailIgnoreCase(authentication.getName())
                .map(AppUser::getId)
                .orElse(null);
    }

    /**
     * Маркеры пользователя по публикациям текущей страницы ленты, ключ — id публикации.
     * Один запрос на страницу (без N+1); пустая карта для анонима или пустой страницы.
     *
     * @param userId     id владельца или {@code null}
     * @param postingIds идентификаторы публикаций страницы
     * @return отображение «id публикации → маркер» (только для помеченных публикаций)
     */
    public Map<Long, SavedPosting> markersByPostingId(Long userId, List<Long> postingIds) {
        if (userId == null || postingIds.isEmpty()) {
            return Map.of();
        }
        return markers.findByUser_IdAndPosting_IdIn(userId, postingIds).stream()
                .collect(Collectors.toMap(m -> m.getPosting().getId(), Function.identity()));
    }
}
