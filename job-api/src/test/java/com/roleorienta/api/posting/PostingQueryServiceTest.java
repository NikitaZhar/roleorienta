package com.roleorienta.api.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roleorienta.api.posting.PostingDtos.Card;
import com.roleorienta.api.posting.PostingDtos.Page;
import com.roleorienta.api.posting.PostingDtos.Skill;
import com.roleorienta.api.posting.PostingDtos.Summary;
import com.roleorienta.api.posting.PostingReadRepository.PostedKeyset;
import com.roleorienta.api.saved.SavedPosting;
import com.roleorienta.api.saved.SavedState;
import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.LanguageMention;
import com.roleorienta.core.domain.LanguageModality;
import com.roleorienta.core.domain.PostingLanguage;
import com.roleorienta.core.domain.PostingSkill;
import com.roleorienta.core.domain.RequirementModality;
import com.roleorienta.core.domain.SeniorityLevel;
import com.roleorienta.core.domain.SkillStance;
import com.roleorienta.core.domain.WorkModality;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;

/**
 * Модульные тесты чтения публикаций (§7, §31) без БД и web: курсорная пагинация, проброс
 * курсора/фильтра, отображение в DTO, а также персонализация ленты (исключение скрытых и
 * пометка элементов) на mock-репозиториях и mock-{@link FeedPersonalization}. Корректность
 * самих SQL-фильтров проверяется на стенде и web-интеграционными тестами.
 */
class PostingQueryServiceTest {

    private static final PostingFilter NO_FILTER = new PostingFilter(null, null, null, null, null);

    private final PostingReadRepository postingRepository = mock(PostingReadRepository.class);
    private final PostingLanguageReadRepository languageRepository = mock(PostingLanguageReadRepository.class);
    private final PostingSkillReadRepository skillRepository = mock(PostingSkillReadRepository.class);
    private final FeedPersonalization personalization = mock(FeedPersonalization.class);
    private final PostingQueryService service = new PostingQueryService(
            postingRepository, languageRepository, skillRepository, personalization);

    /** По умолчанию — анонимный запрос: пользователя нет, маркеров нет. */
    {
        when(personalization.currentUserId(nullable(Authentication.class))).thenReturn(null);
        when(personalization.markersByPostingId(nullable(Long.class), anyList())).thenReturn(Map.of());
    }

    @Test
    void nextCursorSetWhenExtraRowReturned() {
        List<JobPosting> rows = new ArrayList<>();
        for (long id = 1; id <= 21; id++) {
            rows.add(posting(id));
        }
        when(postingRepository.search(eq(0L), any(PostingFilter.class), nullable(Long.class), any(Limit.class)))
                .thenReturn(rows);

        Page page = service.list(new FeedPaging(null, null, null), NO_FILTER, null, false);

        assertThat(page.items()).hasSize(20);
        assertThat(page.nextCursor()).isEqualTo(20L);
    }

    @Test
    void noCursorWhenLastPage() {
        List<JobPosting> rows = List.of(posting(1), posting(2), posting(3));
        when(postingRepository.search(anyLong(), any(PostingFilter.class), nullable(Long.class), any(Limit.class)))
                .thenReturn(rows);

        Page page = service.list(new FeedPaging(null, 20, null), NO_FILTER, null, false);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void cursorIsPassedThrough() {
        when(postingRepository.search(eq(42L), any(PostingFilter.class), nullable(Long.class), any(Limit.class)))
                .thenReturn(List.of());

        Page page = service.list(new FeedPaging(42L, 10, null), NO_FILTER, null, false);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void limitClampedToMaximum() {
        ArgumentCaptor<Limit> captor = ArgumentCaptor.forClass(Limit.class);
        when(postingRepository.search(eq(0L), any(PostingFilter.class), nullable(Long.class), captor.capture()))
                .thenReturn(List.of());

        service.list(new FeedPaging(null, 1000, null), NO_FILTER, null, false);

        assertThat(captor.getValue().max()).isEqualTo(101);
    }

    @Test
    void limitClampedToMinimum() {
        ArgumentCaptor<Limit> captor = ArgumentCaptor.forClass(Limit.class);
        when(postingRepository.search(eq(0L), any(PostingFilter.class), nullable(Long.class), captor.capture()))
                .thenReturn(List.of());

        service.list(new FeedPaging(null, 0, null), NO_FILTER, null, false);

        assertThat(captor.getValue().max()).isEqualTo(2);
    }

    @Test
    void filterIsPassedThrough() {
        ArgumentCaptor<PostingFilter> captor = ArgumentCaptor.forClass(PostingFilter.class);
        when(postingRepository.search(eq(0L), captor.capture(), nullable(Long.class), any(Limit.class)))
                .thenReturn(List.of());
        PostingFilter filter = new PostingFilter(WorkModality.REMOTE, SeniorityLevel.SENIOR, "Germany", null, null);

        service.list(new FeedPaging(null, 10, null), filter, null, false);

        assertThat(captor.getValue()).isEqualTo(filter);
    }

    @Test
    void anonymousFeedIsNotFilteredByHidden() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        when(postingRepository.search(anyLong(), any(PostingFilter.class), captor.capture(), any(Limit.class)))
                .thenReturn(List.of());

        service.list(new FeedPaging(null, 10, null), NO_FILTER, null, false);

        assertThat(captor.getValue()).as("аноним → скрытые не исключаются").isNull();
    }

    @Test
    void authenticatedFeedExcludesHiddenForUser() {
        Authentication auth = mock(Authentication.class);
        when(personalization.currentUserId(auth)).thenReturn(7L);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        when(postingRepository.search(anyLong(), any(PostingFilter.class), captor.capture(), any(Limit.class)))
                .thenReturn(List.of());

        service.list(new FeedPaging(null, 10, null), NO_FILTER, auth, false);

        assertThat(captor.getValue()).isEqualTo(7L);
    }

    @Test
    void includeHiddenKeepsHiddenForAuthenticatedUser() {
        Authentication auth = mock(Authentication.class);
        when(personalization.currentUserId(auth)).thenReturn(7L);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        when(postingRepository.search(anyLong(), any(PostingFilter.class), captor.capture(), any(Limit.class)))
                .thenReturn(List.of());

        service.list(new FeedPaging(null, 10, null), NO_FILTER, auth, true);

        assertThat(captor.getValue()).as("includeHidden=true → не исключаем").isNull();
    }

    @Test
    void summaryIsAnnotatedWithViewerMarker() {
        Authentication auth = mock(Authentication.class);
        when(personalization.currentUserId(auth)).thenReturn(7L);
        when(postingRepository.search(anyLong(), any(PostingFilter.class), nullable(Long.class), any(Limit.class)))
                .thenReturn(List.of(posting(5L)));
        SavedPosting marker = new SavedPosting();
        marker.setState(SavedState.SAVED);
        marker.setSeenAt(Instant.now());
        when(personalization.markersByPostingId(eq(7L), anyList())).thenReturn(Map.of(5L, marker));

        Page page = service.list(new FeedPaging(null, 10, null), NO_FILTER, auth, false);

        assertThat(page.items()).singleElement().satisfies(s -> {
            assertThat(s.viewerState()).isEqualTo(SavedState.SAVED);
            assertThat(s.viewerSeen()).isTrue();
        });
    }

    @Test
    void summaryHasNoViewerMarkerForAnonymous() {
        when(postingRepository.search(anyLong(), any(PostingFilter.class), nullable(Long.class), any(Limit.class)))
                .thenReturn(List.of(posting(5L)));

        Page page = service.list(new FeedPaging(null, 10, null), NO_FILTER, null, false);

        assertThat(page.items()).singleElement().satisfies(s -> {
            assertThat(s.viewerState()).isNull();
            assertThat(s.viewerSeen()).isFalse();
        });
    }

    @Test
    void cardMapsFieldsLanguagesAndSkills() {
        JobPosting posting = posting(5);
        posting.setCity("Berlin");
        posting.setSeniority(SeniorityLevel.SENIOR);
        posting.setExperienceYearsMin(5);
        when(postingRepository.findById(5L)).thenReturn(Optional.of(posting));
        when(languageRepository.findByJobPosting_IdOrderByLanguageCodeAsc(5L))
                .thenReturn(List.of(language("en", LanguageMention.YES, LanguageModality.REQUIRED)));
        when(skillRepository.findByJobPosting_IdOrderBySkillAsc(5L))
                .thenReturn(List.of(skill("Java", SkillStance.REQUESTED, RequirementModality.REQUIRED)));

        Optional<Card> card = service.card(5L);

        assertThat(card).isPresent();
        assertThat(card.get().city()).isEqualTo("Berlin");
        assertThat(card.get().seniority()).isEqualTo(SeniorityLevel.SENIOR);
        assertThat(card.get().experienceYearsMin()).isEqualTo(5);
        assertThat(card.get().languages())
                .extracting(PostingDtos.Language::languageCode, PostingDtos.Language::modality)
                .containsExactly(tuple("en", LanguageModality.REQUIRED));
        assertThat(card.get().skills())
                .extracting(Skill::skill, Skill::stance)
                .containsExactly(tuple("Java", SkillStance.REQUESTED));
    }

    @Test
    void cardEmptyWhenPostingMissing() {
        when(postingRepository.findById(9L)).thenReturn(Optional.empty());

        assertThat(service.card(9L)).isEmpty();
    }

    private JobPosting posting(long id) {
        JobPosting posting = new JobPosting();
        posting.setId(id);
        posting.setExternalId("ext-" + id);
        posting.setUrl("https://example.test/" + id);
        posting.setRawTitle("Posting " + id);
        return posting;
    }

    private PostingLanguage language(String code, LanguageMention mentioned, LanguageModality modality) {
        PostingLanguage language = new PostingLanguage();
        language.setLanguageCode(code);
        language.setMentioned(mentioned);
        language.setModality(modality);
        return language;
    }

    private PostingSkill skill(String name, SkillStance stance, RequirementModality modality) {
        PostingSkill skill = new PostingSkill();
        skill.setSkill(name);
        skill.setStance(stance);
        skill.setModality(modality);
        return skill;
    }

    @Test
    void postedCursorRoundTripIncludingUndated() {
        PostedKeyset dated = new PostedKeyset(java.time.LocalDate.of(2026, 9, 10), 12345L);
        assertThat(PostedKeyset.fromCursor(dated.toCursor())).isEqualTo(dated);
        PostedKeyset undated = new PostedKeyset(PostedKeyset.NO_DATE, 7L);
        assertThat(undated.toCursor()).isPositive();
        // Курсор не больше 2^53: JavaScript-клиент читает его из JSON без потери точности.
        assertThat(new PostedKeyset(java.time.LocalDate.of(9999, 12, 31), (1L << 31) - 1).toCursor())
                .isLessThanOrEqualTo(1L << 53);
        assertThat(PostedKeyset.fromCursor(undated.toCursor())).isEqualTo(undated);
        assertThat(PostedKeyset.fromCursor(null)).isEqualTo(PostedKeyset.FIRST_PAGE);
    }
}
