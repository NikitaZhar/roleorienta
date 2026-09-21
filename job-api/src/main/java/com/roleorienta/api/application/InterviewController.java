package com.roleorienta.api.application;

import com.roleorienta.api.application.InterviewDtos.InterviewResponse;
import com.roleorienta.api.application.InterviewDtos.RescheduleInterviewRequest;
import com.roleorienta.api.application.InterviewDtos.ScheduleInterviewRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-эндпоинты собеседований по отклику (§43).
 *
 * <p>Контроллер тонкий (§3.3): делегирует {@link InterviewService}. Пути под
 * {@code /api/v1/applications/**} требуют входа; {@code POST}/{@code PATCH} несут CSRF-токен.
 * {@code SecurityConfig} не меняется. Приватность — по владельцу отклика (A23).</p>
 */
@RestController
public class InterviewController {

    private final InterviewService service;

    public InterviewController(InterviewService service) {
        this.service = service;
    }

    /** Назначить собеседование. */
    @PostMapping("/api/v1/applications/{applicationId}/interviews")
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewResponse schedule(@PathVariable Long applicationId,
                                      @Valid @RequestBody ScheduleInterviewRequest request,
                                      Authentication authentication) {
        return service.schedule(authentication, applicationId, request.scheduledAt(), request.zoneId());
    }

    /** Собеседования по отклику. */
    @GetMapping("/api/v1/applications/{applicationId}/interviews")
    public List<InterviewResponse> list(@PathVariable Long applicationId,
                                        Authentication authentication) {
        return service.list(authentication, applicationId);
    }

    /** Перенести собеседование. */
    @PatchMapping("/api/v1/applications/{applicationId}/interviews/{interviewId}")
    public InterviewResponse reschedule(@PathVariable Long applicationId,
                                        @PathVariable Long interviewId,
                                        @Valid @RequestBody RescheduleInterviewRequest request,
                                        Authentication authentication) {
        return service.reschedule(authentication, applicationId, interviewId,
                request.scheduledAt(), request.zoneId());
    }

    /** Отменить собеседование (идемпотентно). */
    @PostMapping("/api/v1/applications/{applicationId}/interviews/{interviewId}/cancel")
    public InterviewResponse cancel(@PathVariable Long applicationId,
                                    @PathVariable Long interviewId,
                                    Authentication authentication) {
        return service.cancel(authentication, applicationId, interviewId);
    }
}
