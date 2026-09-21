package com.roleorienta.api.notification;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.notification.NotificationDtos.NotificationResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Чтение уведомлений текущего пользователя (§7).
 *
 * <p><b>Приватность (A23, §3.9).</b> Владелец берётся только из аутентификации (сессии):
 * {@code Authentication.getName()} даёт email, по нему находится {@link AppUser}, и
 * уведомления выбираются по его id. Идентификатор из запроса не принимается — один
 * пользователь не видит уведомлений другого.</p>
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final AppUserRepository users;

    public NotificationService(NotificationRepository notifications, AppUserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    /**
     * Уведомления текущего пользователя (новые сверху).
     *
     * @param authentication текущая аутентификация (владелец)
     * @return уведомления владельца
     */
    @Transactional(readOnly = true)
    public List<NotificationResponse> list(Authentication authentication) {
        AppUser owner = currentUser(authentication);
        return notifications.findByAppUserIdOrderByIdDesc(owner.getId())
                .stream()
                .map(NotificationResponse::of)
                .toList();
    }

    private AppUser currentUser(Authentication authentication) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход");
        }
        return users.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Сессия недействительна"));
    }
}
