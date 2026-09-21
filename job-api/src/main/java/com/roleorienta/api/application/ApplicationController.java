package com.roleorienta.api.application;

import com.roleorienta.api.application.ApplicationDtos.AddNoteRequest;
import com.roleorienta.api.application.ApplicationDtos.ApplicationCardResponse;
import com.roleorienta.api.application.ApplicationDtos.ApplicationResponse;
import com.roleorienta.api.application.ApplicationDtos.CreateApplicationRequest;
import com.roleorienta.api.application.ApplicationDtos.NoteResponse;
import com.roleorienta.api.application.ApplicationDtos.UpdateNoteRequest;
import com.roleorienta.api.application.ApplicationDtos.UpdateStatusRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-эндпоинты откликов и заметок (§7).
 *
 * <p>Контроллер тонкий (§3.3): делегирует {@link ApplicationService}. Карточка отдаёт
 * сильный {@code ETag} (версия отклика), {@code PATCH} принимает {@code If-Match} (A19).
 * Пути под {@code /api/v1/applications/**} требуют входа; {@code POST}/{@code PATCH} несут
 * CSRF-токен. {@code SecurityConfig} не меняется.</p>
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

    /** Карточка отклика с заметками; отдаёт {@code ETag} версии (A19). */
    @GetMapping("/api/v1/applications/{id}")
    public ResponseEntity<ApplicationCardResponse> get(@PathVariable Long id,
                                                       Authentication authentication) {
        ApplicationCardResponse card = service.get(authentication, id);
        return ResponseEntity.ok().eTag(etag(card.version())).body(card);
    }

    /**
     * Сменить статус отклика. Обязателен {@code If-Match} с текущей версией (A19):
     * нет заголовка → 428, устаревший → 412, недопустимый переход или гонка → 409.
     */
    @PatchMapping("/api/v1/applications/{id}")
    public ResponseEntity<ApplicationCardResponse> updateStatus(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateStatusRequest request,
            Authentication authentication) {
        ApplicationCardResponse card = service.updateStatus(authentication, id, request.status(), ifMatch);
        return ResponseEntity.ok().eTag(etag(card.version())).body(card);
    }

    /** Добавить заметку к отклику. */
    @PostMapping("/api/v1/applications/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse addNote(@PathVariable Long id,
                                @Valid @RequestBody AddNoteRequest request,
                                Authentication authentication) {
        return service.addNote(authentication, id, request.body());
    }

    /** Изменить текст заметки. */
    @PatchMapping("/api/v1/applications/{id}/notes/{noteId}")
    public NoteResponse updateNote(@PathVariable Long id,
                                   @PathVariable Long noteId,
                                   @Valid @RequestBody UpdateNoteRequest request,
                                   Authentication authentication) {
        return service.updateNote(authentication, id, noteId, request.body());
    }

    /** Удалить заметку. */
    @DeleteMapping("/api/v1/applications/{id}/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable Long id,
                           @PathVariable Long noteId,
                           Authentication authentication) {
        service.deleteNote(authentication, id, noteId);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}
