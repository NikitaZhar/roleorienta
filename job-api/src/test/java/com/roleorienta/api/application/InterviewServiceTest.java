package com.roleorienta.api.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.api.application.InterviewDtos.InterviewResponse;
import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика собеседований (§43): резолв владельца через владельца отклика (A23), валидация
 * таймзоны и времени (400), перенос/отмена (409 на перенос отменённого, идемпотентная отмена).
 * Репозитории и аутентификация — заглушки.
 */
class InterviewServiceTest {

    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ApplicationService applicationService = mock(ApplicationService.class);
    private final InterviewService service =
            new InterviewService(interviews, applications, users, applicationService);

    private final Authentication auth = mock(Authentication.class);
    private final AppUser owner = mock(AppUser.class);
    private final Application application = mock(Application.class);

    private static final Instant FUTURE = Instant.now().plusSeconds(3600);
    private static final Instant PAST = Instant.now().minusSeconds(3600);
    private static final String ZONE = "Europe/Amsterdam";

    private void withOwner() {
        when(auth.getName()).thenReturn("u@example.com");
        when(owner.getId()).thenReturn(1L);
        when(users.findByEmailIgnoreCase("u@example.com")).thenReturn(Optional.of(owner));
    }

    private void ownedApplication() {
        when(applications.findByIdAndUser_Id(5L, 1L)).thenReturn(Optional.of(application));
    }

    private Interview ownedInterview(InterviewStatus status) {
        Interview interview = new Interview();
        interview.setApplication(application);
        interview.setScheduledAt(FUTURE);
        interview.setZoneId(ZONE);
        interview.setStatus(status);
        when(interviews.findByIdAndApplication_IdAndApplication_User_Id(9L, 5L, 1L))
                .thenReturn(Optional.of(interview));
        return interview;
    }

    private static int statusOf(ResponseStatusException e) {
        return e.getStatusCode().value();
    }

    @Test
    void scheduleValidInterview() {
        withOwner();
        ownedApplication();
        when(interviews.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterviewResponse response = service.schedule(auth, 5L, FUTURE, ZONE);

        ArgumentCaptor<Interview> captor = ArgumentCaptor.forClass(Interview.class);
        verify(interviews).save(captor.capture());
        assertEquals(application, captor.getValue().getApplication());
        assertEquals(InterviewStatus.SCHEDULED, captor.getValue().getStatus());
        assertEquals("SCHEDULED", response.status());
        assertEquals(ZONE, response.zoneId());
        verify(applicationService).markInterviewing(application);
    }

    @Test
    void scheduleForeignApplicationIsNotFound() {
        withOwner();
        when(applications.findByIdAndUser_Id(5L, 1L)).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.schedule(auth, 5L, FUTURE, ZONE));
        assertEquals(404, statusOf(e));
        verify(interviews, never()).save(any());
    }

    @Test
    void scheduleInvalidZoneIsBadRequest() {
        withOwner();
        ownedApplication();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.schedule(auth, 5L, FUTURE, "Mars/Olympus"));
        assertEquals(400, statusOf(e));
        verify(interviews, never()).save(any());
    }

    @Test
    void schedulePastTimeIsBadRequest() {
        withOwner();
        ownedApplication();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.schedule(auth, 5L, PAST, ZONE));
        assertEquals(400, statusOf(e));
        verify(interviews, never()).save(any());
    }

    @Test
    void rescheduleUpdatesTime() {
        withOwner();
        Interview interview = ownedInterview(InterviewStatus.SCHEDULED);
        when(interviews.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Instant later = Instant.now().plusSeconds(7200);

        service.reschedule(auth, 5L, 9L, later, "America/New_York");

        assertEquals(later, interview.getScheduledAt());
        assertEquals("America/New_York", interview.getZoneId());
        verify(interviews).save(interview);
    }

    @Test
    void rescheduleCancelledIsConflict() {
        withOwner();
        ownedInterview(InterviewStatus.CANCELLED);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.reschedule(auth, 5L, 9L, FUTURE, ZONE));
        assertEquals(409, statusOf(e));
        verify(interviews, never()).save(any());
    }

    @Test
    void cancelSetsCancelled() {
        withOwner();
        Interview interview = ownedInterview(InterviewStatus.SCHEDULED);
        when(interviews.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterviewResponse response = service.cancel(auth, 5L, 9L);

        assertEquals(InterviewStatus.CANCELLED, interview.getStatus());
        assertEquals("CANCELLED", response.status());
        verify(interviews).save(interview);
    }

    @Test
    void cancelAlreadyCancelledIsIdempotent() {
        withOwner();
        ownedInterview(InterviewStatus.CANCELLED);
        InterviewResponse response = service.cancel(auth, 5L, 9L);
        assertEquals("CANCELLED", response.status());
        verify(interviews, never()).save(any());
    }
}
