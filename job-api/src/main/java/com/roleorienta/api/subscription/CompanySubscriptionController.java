package com.roleorienta.api.subscription;

import com.roleorienta.api.subscription.CompanySubscriptionDtos.SubscriptionResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-эндпоинты подписок на компании (§7).
 *
 * <p>Контроллер тонкий (§3.3): передаёт {@link Authentication} и id в
 * {@link CompanySubscriptionService}. Пути версионированы. Доступ: операции под
 * {@code /api/v1/companies/**} и список под {@code /api/v1/me/**} требуют входа по
 * общему правилу {@code anyRequest().authenticated()} — {@code SecurityConfig} не
 * меняется (открыт только {@code GET /api/v1/postings/**}). {@code PUT}/{@code DELETE}
 * несут CSRF-токен по общей политике.</p>
 */
@RestController
public class CompanySubscriptionController {

    private final CompanySubscriptionService service;

    public CompanySubscriptionController(CompanySubscriptionService service) {
        this.service = service;
    }

    /** Подписаться на компанию (идемпотентно). */
    @PutMapping("/api/v1/companies/{id}/subscription")
    public SubscriptionResponse subscribe(@PathVariable Long id, Authentication authentication) {
        return service.subscribe(authentication, id);
    }

    /** Отписаться от компании (идемпотентно). */
    @DeleteMapping("/api/v1/companies/{id}/subscription")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long id, Authentication authentication) {
        service.unsubscribe(authentication, id);
        return ResponseEntity.noContent().build();
    }

    /** Подписки текущего пользователя. */
    @GetMapping("/api/v1/me/company-subscriptions")
    public List<SubscriptionResponse> list(Authentication authentication) {
        return service.list(authentication);
    }
}
