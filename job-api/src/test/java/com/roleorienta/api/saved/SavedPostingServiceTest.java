package com.roleorienta.api.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.saved.SavedPostingDtos.SavedPostingResponse;
import com.roleorienta.core.domain.JobPosting;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Модульные тесты {@link SavedPostingService} на моках (без БД и Spring): owner-логика (A23)
 * и правила сохранить/скрыть/просмотрено/снять/список напрямую — быстро, без Docker.
 * Поведение веб-слоя, реального SQL и CSRF проверяют интеграционные тесты.
 */
class SavedPostingServiceTest {

    private static final String EMAIL = "owner@example.com";
    private static final long USER_ID = 7L;
    private static final long POSTING_ID = 100L;

    private SavedPostingRepository markers;
    private AppUserRepository users;
    private MarkedPostingRepository postings;
    private SavedPostingService service;

    private Authentication auth;
    private AppUser owner;
    private JobPosting posting;

    @BeforeEach
    void setUp() {
        markers = mock(SavedPostingRepository.class);
        users = mock(AppUserRepository.class);
        postings = mock(MarkedPostingRepository.class);
        service = new SavedPostingService(markers, users, postings);

        auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(EMAIL);

        owner = mock(AppUser.class);
        when(owner.getId()).thenReturn(USER_ID);
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(owner));

        posting = mock(JobPosting.class);
        when(posting.getId()).thenReturn(POSTING_ID);
        when(postings.findById(POSTING_ID)).thenReturn(Optional.of(posting));

        when(markers.save(any(SavedPosting.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void saveCreatesMarkerInSavedState() {
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.empty());

        SavedPostingResponse response = service.save(auth, POSTING_ID);

        assertThat(response.state()).isEqualTo(SavedState.SAVED);
        assertThat(response.postingId()).isEqualTo(POSTING_ID);
        assertThat(response.hiddenReason()).isNull();
    }

    @Test
    void saveOverwritesPreviousHiddenAndClearsReason() {
        SavedPosting existing = new SavedPosting();
        existing.setUser(owner);
        existing.setPosting(posting);
        existing.setState(SavedState.HIDDEN);
        existing.setHiddenReason("не интересно");
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.of(existing));

        SavedPostingResponse response = service.save(auth, POSTING_ID);

        assertThat(response.state()).isEqualTo(SavedState.SAVED);
        assertThat(response.hiddenReason()).isNull();
    }

    @Test
    void saveKeepsExistingSeen() {
        SavedPosting existing = new SavedPosting();
        existing.setUser(owner);
        existing.setPosting(posting);
        existing.setSeenAt(Instant.now());
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.of(existing));

        SavedPostingResponse response = service.save(auth, POSTING_ID);

        assertThat(response.state()).isEqualTo(SavedState.SAVED);
        assertThat(response.seenAt()).isNotNull();
    }

    @Test
    void hideStoresReason() {
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.empty());

        SavedPostingResponse response = service.hide(auth, POSTING_ID, "дубликат вакансии");

        assertThat(response.state()).isEqualTo(SavedState.HIDDEN);
        assertThat(response.hiddenReason()).isEqualTo("дубликат вакансии");
    }

    @Test
    void markSeenSetsSeenAtWithoutState() {
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.empty());

        SavedPostingResponse response = service.markSeen(auth, POSTING_ID);

        assertThat(response.seenAt()).isNotNull();
        assertThat(response.state()).isNull();
    }

    @Test
    void markSeenIsIdempotentAndKeepsFirstSeenInstant() {
        Instant firstSeen = Instant.parse("2026-01-01T00:00:00Z");
        SavedPosting existing = new SavedPosting();
        existing.setUser(owner);
        existing.setPosting(posting);
        existing.setState(SavedState.SAVED);
        existing.setSeenAt(firstSeen);
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.of(existing));

        SavedPostingResponse response = service.markSeen(auth, POSTING_ID);

        assertThat(response.seenAt()).isEqualTo(firstSeen);
        assertThat(response.state()).isEqualTo(SavedState.SAVED);
    }

    @Test
    void saveOnMissingPostingReturnsNotFoundAndDoesNotWrite() {
        when(postings.findById(POSTING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(auth, POSTING_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(markers, never()).save(any());
    }

    @Test
    void listSavedReturnsOwnerSavedMarkers() {
        SavedPosting marker = new SavedPosting();
        marker.setUser(owner);
        marker.setPosting(posting);
        marker.setState(SavedState.SAVED);
        when(markers.findByUser_IdAndStateOrderByIdDesc(USER_ID, SavedState.SAVED))
                .thenReturn(List.of(marker));

        List<SavedPostingResponse> result = service.listSaved(auth);

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.postingId()).isEqualTo(POSTING_ID);
            assertThat(r.state()).isEqualTo(SavedState.SAVED);
        });
    }

    @Test
    void removeDeletesMarkerWithoutSeen() {
        SavedPosting marker = new SavedPosting();
        marker.setUser(owner);
        marker.setPosting(posting);
        marker.setState(SavedState.SAVED);
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.of(marker));

        service.remove(auth, POSTING_ID);

        verify(markers).delete(marker);
    }

    @Test
    void removeKeepsMarkerWhenSeenPreserved() {
        SavedPosting marker = new SavedPosting();
        marker.setUser(owner);
        marker.setPosting(posting);
        marker.setState(SavedState.SAVED);
        marker.setSeenAt(Instant.now());
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.of(marker));

        service.remove(auth, POSTING_ID);

        verify(markers, never()).delete(any());
        verify(markers).save(marker);
        assertThat(marker.getState()).isNull();
    }

    @Test
    void removeIsNoOpWhenMarkerAbsent() {
        when(markers.findByUser_IdAndPosting_Id(USER_ID, POSTING_ID)).thenReturn(Optional.empty());

        service.remove(auth, POSTING_ID);

        verify(markers, never()).delete(any());
        verify(markers, never()).save(any());
    }

    @Test
    void unknownUserIsUnauthorized() {
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(auth, POSTING_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
