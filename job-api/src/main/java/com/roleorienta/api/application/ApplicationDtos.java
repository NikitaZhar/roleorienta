package com.roleorienta.api.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** Тела запросов и ответов эндпоинтов откликов и заметок (§7). */
public final class ApplicationDtos {

    private ApplicationDtos() {
    }

    /** Создать отклик на публикацию. */
    public record CreateApplicationRequest(@NotNull Long postingId) {
    }

    /** Добавить заметку к отклику. */
    public record AddNoteRequest(@NotBlank String body) {
    }

    /** Сменить статус отклика (A19: предусловие If-Match — в заголовке). */
    public record UpdateStatusRequest(@NotNull ApplicationStatus status) {
    }

    /** Отклик в списке. */
    public record ApplicationResponse(Long id, Long postingId, String status, Instant createdAt) {

        static ApplicationResponse of(Application application) {
            return new ApplicationResponse(
                    application.getId(),
                    application.getPosting().getId(),
                    application.getStatus().name(),
                    application.getCreatedAt());
        }
    }

    /** Заметка. */
    public record NoteResponse(Long id, String body, Instant createdAt) {

        static NoteResponse of(ApplicationNote note) {
            return new NoteResponse(note.getId(), note.getBody(), note.getCreatedAt());
        }
    }

    /** Карточка отклика с заметками. */
    public record ApplicationCardResponse(Long id, Long postingId, String status,
                                          long version, Instant createdAt, List<NoteResponse> notes) {

        static ApplicationCardResponse of(Application application, List<ApplicationNote> notes) {
            return new ApplicationCardResponse(
                    application.getId(),
                    application.getPosting().getId(),
                    application.getStatus().name(),
                    application.getVersion(),
                    application.getCreatedAt(),
                    notes.stream().map(NoteResponse::of).toList());
        }
    }
}
