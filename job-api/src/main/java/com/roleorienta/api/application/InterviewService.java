package com.roleorienta.api.application;

import com.roleorienta.api.application.InterviewDtos.InterviewResponse;
import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика собеседований по отклику (§43) — всегда в пределах владельца отклика (A23).
 *
 * <p><b>Приватность (A23, §3.9).</b> Собеседование доступно только через владельца отклика:
 * репозиторий ищет по {@code (interviewId, applicationId, ownerId)}, доступ к чужому — {@code 404}.</p>
 *
 * <p><b>Время и таймзона.</b> Момент хранится в UTC ({@code scheduledAt}); таймзона
 * ({@code zoneId}, IANA) валидируется через {@link ZoneId#of(String)} — некорректная → {@code 400}.
 * Назначение и перенос только в будущее — прошедшее время → {@code 400}. Перенос отменённого
 * собеседования → {@code 409}; отмена уже отменённого — идемпотентный успех.</p>
 */
@Service
public class InterviewService {

    private final InterviewRepository interviews;
    private final ApplicationRepository applications;
    private final AppUserRepository users;
    private final ApplicationService applicationService;

    public InterviewService(InterviewRepository interviews,
                            ApplicationRepository applications,
                            AppUserRepository users,
                            ApplicationService applicationService) {
        this.interviews = interviews;
        this.applications = applications;
        this.users = users;
        this.applicationService = applicationService;
    }

    /**
     * Назначить собеседование по отклику. Побочный эффект (§45): отклик в статусе
     * {@code APPLIED} поднимается до {@code INTERVIEWING}.
     *
     * @throws ResponseStatusException 404 (отклик чужой/не найден), 400 (таймзона/прошедшее время)
     */
    @Transactional
    public InterviewResponse schedule(Authentication authentication, Long applicationId,
                                      Instant scheduledAt, String zoneId) {
        AppUser owner = currentUser(authentication);
        Application application = requireOwnedApplication(applicationId, owner);
        validateZone(zoneId);
        requireFuture(scheduledAt);

        Interview interview = new Interview();
        interview.setApplication(application);
        interview.setScheduledAt(scheduledAt);
        interview.setZoneId(zoneId);
        interview.setStatus(InterviewStatus.SCHEDULED);
        Interview saved = interviews.save(interview);

        // §45: назначение собеседования поднимает отклик APPLIED → INTERVIEWING.
        applicationService.markInterviewing(application);
        return InterviewResponse.of(saved);
    }

    /** Собеседования по отклику (ближайшие сверху). */
    @Transactional(readOnly = true)
    public List<InterviewResponse> list(Authentication authentication, Long applicationId) {
        AppUser owner = currentUser(authentication);
        requireOwnedApplication(applicationId, owner);
        return interviews.findByApplication_IdOrderByScheduledAtAsc(applicationId)
                .stream()
                .map(InterviewResponse::of)
                .toList();
    }

    /**
     * Перенести собеседование (новое время/таймзона), пока оно не отменено.
     *
     * @throws ResponseStatusException 404 (чужое/не найдено), 409 (отменено),
     *                                 400 (таймзона/прошедшее время)
     */
    @Transactional
    public InterviewResponse reschedule(Authentication authentication, Long applicationId,
                                        Long interviewId, Instant scheduledAt, String zoneId) {
        AppUser owner = currentUser(authentication);
        Interview interview = requireOwnedInterview(interviewId, applicationId, owner);
        if (interview.getStatus() == InterviewStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Собеседование отменено, перенос невозможен");
        }
        validateZone(zoneId);
        requireFuture(scheduledAt);

        interview.setScheduledAt(scheduledAt);
        interview.setZoneId(zoneId);
        return InterviewResponse.of(interviews.save(interview));
    }

    /**
     * Отменить собеседование. Повтор отмены — идемпотентный успех (RFC 9110).
     *
     * @throws ResponseStatusException 404 (чужое/не найдено)
     */
    @Transactional
    public InterviewResponse cancel(Authentication authentication, Long applicationId, Long interviewId) {
        AppUser owner = currentUser(authentication);
        Interview interview = requireOwnedInterview(interviewId, applicationId, owner);
        if (interview.getStatus() != InterviewStatus.CANCELLED) {
            interview.setStatus(InterviewStatus.CANCELLED);
            interview = interviews.save(interview);
        }
        return InterviewResponse.of(interview);
    }

    private void validateZone(String zoneId) {
        try {
            ZoneId.of(zoneId);
        } catch (DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Некорректная таймзона (ожидается IANA zone id): " + zoneId);
        }
    }

    private void requireFuture(Instant scheduledAt) {
        if (!scheduledAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Время собеседования должно быть в будущем");
        }
    }

    private Application requireOwnedApplication(Long applicationId, AppUser owner) {
        return applications.findByIdAndUser_Id(applicationId, owner.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Отклик не найден: " + applicationId));
    }

    private Interview requireOwnedInterview(Long interviewId, Long applicationId, AppUser owner) {
        return interviews
                .findByIdAndApplication_IdAndApplication_User_Id(interviewId, applicationId, owner.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Собеседование не найдено: " + interviewId));
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
