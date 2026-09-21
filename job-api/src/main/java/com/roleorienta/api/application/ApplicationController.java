package com.roleorienta.api.application;

import com.roleorienta.api.application.ApplicationDtos.AddNoteRequest;
import com.roleorienta.api.application.ApplicationDtos.ApplicationCardResponse;
import com.roleorienta.api.application.ApplicationDtos.ApplicationResponse;
import com.roleorienta.api.application.ApplicationDtos.CreateApplicationRequest;
import com.roleorienta.api.application.ApplicationDtos.NoteResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-эндпоинты откликов и заметок (§7).
 *
 * <p>Контроллер тонкий (§3.3): делегирует {@link ApplicationService}. Пути под
 * {@code /api/v1/applications/**} требуют входа по общему правилу
 * {@code anyRequest().authenticated()} — {@code SecurityConfig} не меняется;
 * {@code POST} несут CSRF-токен.</p>
 */
@RestController
public class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    /** Создать отклик (идемпотентно). */
    @PostMapping("/api/v1/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse create(@Valid @RequestBody CreateApplicationRequest request,
                                      Authentication authentication) {
        return service.create(authentication, request.postingId());
    }

    /** Отклики текущего пользователя. */
    @GetMapping("/api/v1/applications")
    public List<ApplicationResponse> list(Authentication authentication) {
        return service.list(authentication);
    }

    /** Карточка отклика с заметками. */
    @GetMapping("/api/v1/applications/{id}")
    public ApplicationCardResponse get(@PathVariable Long id, Authentication authentication) {
        return service.get(authentication, id);
    }

    /** Добавить заметку к отклику. */
    @PostMapping("/api/v1/applications/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse addNote(@PathVariable Long id,
                                @Valid @RequestBody AddNoteRequest request,
                                Authentication authentication) {
        return service.addNote(authentication, id, request.body());
    }
}
