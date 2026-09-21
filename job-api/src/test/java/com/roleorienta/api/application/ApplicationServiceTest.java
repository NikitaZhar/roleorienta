package com.roleorienta.api.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.api.application.ApplicationDtos.ApplicationResponse;
import com.roleorienta.api.application.ApplicationDtos.NoteResponse;
import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.posting.PostingReadRepository;
import com.roleorienta.core.domain.JobPosting;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика откликов и заметок (§41): резолв владельца (A23), идемпотентность создания,
 * 404 на нет-публикацию и на чужой отклик. Репозитории и аутентификация — заглушки.
 */
class ApplicationServiceTest {

    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final ApplicationNoteRepository notes = mock(ApplicationNoteRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PostingReadRepository postings = mock(PostingReadRepository.class);
    private final ApplicationService service =
            new ApplicationService(applications, notes, users, postings);

    private final Authentication auth = mock(Authentication.class);
    private final AppUser owner = mock(AppUser.class);
    private final JobPosting posting = mock(JobPosting.class);

    private void withOwner() {
        when(auth.getName()).thenReturn("u@example.com");
        when(owner.getId()).thenReturn(1L);
        when(users.findByEmailIgnoreCase("u@example.com")).thenReturn(Optional.of(owner));
    }

    @Test
    void createNewApplication() {
        withOwner();
        when(posting.getId()).thenReturn(10L);
        when(postings.findById(10L)).thenReturn(Optional.of(posting));
        when(applications.findByUser_IdAndPosting_Id(1L, 10L)).thenReturn(Optional.empty());
        when(applications.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApplicationResponse response = service.create(auth, 10L);

        ArgumentCaptor<Application> captor = ArgumentCaptor.forClass(Application.class);
        verify(applications).save(captor.capture());
        assertEquals(owner, captor.getValue().getUser());
        assertEquals(ApplicationStatus.APPLIED, captor.getValue().getStatus());
        assertEquals(10L, response.postingId());
        assertEquals("APPLIED", response.status());
    }

    @Test
    void createIsIdempotentForExisting() {
        withOwner();
        when(postings.findById(10L)).thenReturn(Optional.of(posting));
        Application existing = new Application();
        existing.setPosting(posting);
        existing.setStatus(ApplicationStatus.INTERVIEWING);
        when(posting.getId()).thenReturn(10L);
        when(applications.findByUser_IdAndPosting_Id(1L, 10L)).thenReturn(Optional.of(existing));

        ApplicationResponse response = service.create(auth, 10L);

        verify(applications, never()).save(any());
        assertEquals("INTERVIEWING", response.status());
    }

    @Test
    void createMissingPostingIsNotFound() {
        withOwner();
        when(postings.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.create(auth, 999L));
        verify(applications, never()).save(any());
    }

    @Test
    void getForeignApplicationIsNotFound() {
        withOwner();
        when(applications.findByIdAndUser_Id(5L, 1L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.get(auth, 5L));
    }

    @Test
    void addNoteToOwnedApplication() {
        withOwner();
        Application application = new Application();
        when(applications.findByIdAndUser_Id(5L, 1L)).thenReturn(Optional.of(application));
        when(notes.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NoteResponse response = service.addNote(auth, 5L, "call recruiter");

        ArgumentCaptor<ApplicationNote> captor = ArgumentCaptor.forClass(ApplicationNote.class);
        verify(notes).save(captor.capture());
        assertEquals(application, captor.getValue().getApplication());
        assertEquals("call recruiter", response.body());
    }
}
