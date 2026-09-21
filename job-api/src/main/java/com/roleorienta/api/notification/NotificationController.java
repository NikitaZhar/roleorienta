package com.roleorienta.api.notification;

import com.roleorienta.api.notification.NotificationDtos.NotificationResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-эндпоинт уведомлений (§7).
 *
 * <p>Контроллер тонкий (§3.3): делегирует {@link NotificationService}. Путь
 * {@code /api/v1/notifications} требует входа по общему правилу
 * {@code anyRequest().authenticated()} — {@code SecurityConfig} не меняется.</p>
 */
@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** Уведомления текущего пользователя. */
    @GetMapping("/api/v1/notifications")
    public List<NotificationResponse> list(Authentication authentication) {
        return service.list(authentication);
    }
}
