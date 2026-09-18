package com.roleorienta.api.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.saved.SavedPosting;
import com.roleorienta.api.saved.SavedPostingRepository;
import com.roleorienta.core.domain.JobPosting;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Модульные тесты {@link FeedPersonalization} на моках: резолв текущего пользователя из
 * аутентификации (аноним → {@code null}, A23) и построение карты маркеров по публикациям
 * страницы. Без БД и Spring.
 */
class FeedPersonalizationTest {

    private static final String EMAIL = "u@example.com";
    private static final long USER_ID = 7L;

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SavedPostingRepository markers = mock(SavedPostingRepository.class);
    private final FeedPersonalization personalization = new FeedPersonalization(users, markers);

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = mock(AppUser.class);
        when(user.getId()).thenReturn(USER_ID);
    }

    @Test
    void currentUserIdIsNullForAnonymousRequest() {
        assertThat(personalization.currentUserId(null)).isNull();
    }

    @Test
    void currentUserIdIsNullWhenUserNotFound() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("anonymousUser");
        when(users.findByEmailIgnoreCase("anonymousUser")).thenReturn(Optional.empty());

        assertThat(personalization.currentUserId(auth)).isNull();
    }

    @Test
    void currentUserIdResolvesAuthenticatedUser() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(EMAIL);
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        assertThat(personalization.currentUserId(auth)).isEqualTo(USER_ID);
    }

    @Test
    void markersAreEmptyForAnonymousOrEmptyPage() {
        assertThat(personalization.markersByPostingId(null, List.of(1L, 2L))).isEmpty();
        assertThat(personalization.markersByPostingId(USER_ID, List.of())).isEmpty();
    }

    @Test
    void markersAreKeyedByPostingId() {
        SavedPosting marker = new SavedPosting();
        marker.setPosting(posting(5L));
        when(markers.findByUser_IdAndPosting_IdIn(USER_ID, List.of(5L))).thenReturn(List.of(marker));

        Map<Long, SavedPosting> result = personalization.markersByPostingId(USER_ID, List.of(5L));

        assertThat(result).containsOnlyKeys(5L);
        assertThat(result.get(5L)).isSameAs(marker);
    }

    private JobPosting posting(long id) {
        JobPosting posting = new JobPosting();
        posting.setId(id);
        return posting;
    }
}
