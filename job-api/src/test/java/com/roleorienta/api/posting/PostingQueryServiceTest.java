package com.roleorienta.api.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roleorienta.api.posting.PostingDtos.Card;
import com.roleorienta.api.posting.PostingDtos.Page;
import com.roleorienta.api.posting.PostingDtos.Skill;
import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.LanguageMention;
import com.roleorienta.core.domain.LanguageModality;
import com.roleorienta.core.domain.PostingLanguage;
import com.roleorienta.core.domain.PostingSkill;
import com.roleorienta.core.domain.RequirementModality;
import com.roleorienta.core.domain.SeniorityLevel;
import com.roleorienta.core.domain.SkillStance;
import com.roleorienta.core.domain.WorkModality;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

/**
 * Модульные тесты чтения публикаций (§7) без БД и web: курсорная пагинация (курсор
 * следующей страницы, обрезка размера), проброс курсора и фильтра, отображение сущностей
 * в DTO — на mock-репозиториях. Корректность самих SQL-фильтров проверяется на стенде и
 * будущим web-интеграционным тестом.
 */
class PostingQueryServiceTest {

    private static final PostingFilter NO_FILTER = new PostingFilter(null, null, null, null);

    private final PostingReadRepository postingRepository = mock(PostingReadRepository.class);
    private final PostingLanguageReadRepository languageRepository = mock(PostingLanguageReadRepository.class);
    private final PostingSkillReadRepository skillRepository = mock(PostingSkillReadRepository.class);
    private final PostingQueryService service =
            new PostingQueryService(postingRepository, languageRepository, skillRepository);

    @Test
    void nextCursorSetWhenExtraRowReturned() {
        List<JobPosting> rows = new ArrayList<>();
        for (long id = 1; id <= 21; id++) {
            rows.add(posting(id));
        }
        when(postingRepository.search(eq(0L), any(PostingFilter.class), any(Limit.class))).thenReturn(rows);

        Page page = service.list(null, null, NO_FILTER);

        assertThat(page.items()).hasSize(20);
        assertThat(page.nextCursor()).isEqualTo(20L);
    }

    @Test
    void noCursorWhenLastPage() {
        List<JobPosting> rows = List.of(posting(1), posting(2), posting(3));
        when(postingRepository.search(anyLong(), any(PostingFilter.class), any(Limit.class))).thenReturn(rows);

        Page page = service.list(null, 20, NO_FILTER);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void cursorIsPassedThrough() {
        when(postingRepository.search(eq(42L), any(PostingFilter.class), any(Limit.class))).thenReturn(List.of());

        Page page = service.list(42L, 10, NO_FILTER);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void limitClampedToMaximum() {
        ArgumentCaptor<Limit> captor = ArgumentCaptor.forClass(Limit.class);
        when(postingRepository.search(eq(0L), any(PostingFilter.class), captor.capture())).thenReturn(List.of());

        service.list(null, 1000, NO_FILTER);

        assertThat(captor.getValue().max()).isEqualTo(101);
    }

    @Test
    void limitClampedToMinimum() {
        ArgumentCaptor<Limit> captor = ArgumentCaptor.forClass(Limit.class);
        when(postingRepository.search(eq(0L), any(PostingFilter.class), captor.capture())).thenReturn(List.of());

        service.list(null, 0, NO_FILTER);

        assertThat(captor.getValue().max()).isEqualTo(2);
    }

    @Test
    void filterIsPassedThrough() {
        ArgumentCaptor<PostingFilter> captor = ArgumentCaptor.forClass(PostingFilter.class);
        when(postingRepository.search(eq(0L), captor.capture(), any(Limit.class))).thenReturn(List.of());
        PostingFilter filter = new PostingFilter(WorkModality.REMOTE, SeniorityLevel.SENIOR, "Germany", null);

        service.list(null, 10, filter);

        assertThat(captor.getValue()).isEqualTo(filter);
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
}
