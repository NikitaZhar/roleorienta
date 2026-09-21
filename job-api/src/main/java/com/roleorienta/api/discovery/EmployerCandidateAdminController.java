package com.roleorienta.api.discovery;

import com.roleorienta.api.discovery.EmployerCandidateDtos.CandidateResponse;
import com.roleorienta.api.discovery.EmployerCandidateDtos.ConfirmRequest;
import com.roleorienta.api.discovery.EmployerCandidateDtos.DiscoverRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Админ-эндпоинты очереди подтверждения кандидатов в работодатели (§7, §33).
 *
 * <p>Доступ — только роль {@code ADMIN} (правило в {@code SecurityConfig} для
 * {@code /api/v1/admin/**}). Контроллер тонкий: делегирует
 * {@link EmployerDiscoveryService}. Запуск обнаружения асинхронный —
 * {@code 202 Accepted} (§7): фактическую проверку ленты выполнит job-worker.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/employer-candidates")
public class EmployerCandidateAdminController {

    private final EmployerDiscoveryService service;

    public EmployerCandidateAdminController(EmployerDiscoveryService service) {
        this.service = service;
    }

    /** Очередь кандидатов, новые сверху. */
    @GetMapping
    public List<CandidateResponse> list() {
        return service.list();
    }

    /** Запустить обнаружение по явному кандидату (провайдер + slug + базовый адрес). */
    @PostMapping("/discover")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void discover(@Valid @RequestBody DiscoverRequest request) {
        service.discover(request);
    }

    /** Подтвердить кандидата: завести источник и поставить на сбор. */
    @PostMapping("/{id}/confirm")
    public CandidateResponse confirm(@PathVariable Long id,
                                     @RequestBody(required = false) ConfirmRequest request) {
        String companyName = request == null ? null : request.companyName();
        return service.confirm(id, companyName);
    }

    /** Отклонить кандидата. */
    @PostMapping("/{id}/reject")
    public CandidateResponse reject(@PathVariable Long id) {
        return service.reject(id);
    }
}
