package com.roleorienta.api.posting;

import com.roleorienta.api.posting.PostingDtos.Card;
import com.roleorienta.api.posting.PostingDtos.Coverage;
import com.roleorienta.api.posting.PostingDtos.Experience;
import com.roleorienta.api.posting.PostingDtos.Facts;
import com.roleorienta.api.posting.PostingDtos.Head;
import com.roleorienta.api.posting.PostingDtos.Language;
import com.roleorienta.api.posting.PostingDtos.Location;
import com.roleorienta.api.posting.PostingDtos.Page;
import com.roleorienta.api.posting.PostingDtos.Requirements;
import com.roleorienta.api.posting.PostingDtos.Salary;
import com.roleorienta.api.posting.PostingDtos.Skill;
import com.roleorienta.api.posting.PostingDtos.Summary;
import com.roleorienta.api.posting.PostingDtos.Timeline;
import com.roleorienta.api.posting.PostingDtos.Viewer;
import com.roleorienta.api.saved.SavedPosting;
import com.roleorienta.core.domain.CoverageAssessment;
import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.PostingLanguage;
import com.roleorienta.core.domain.PostingSkill;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Чтение публикаций для REST-выдачи (§7): лента с курсорной пагинацией и карточка.
 *
 * <p>Читает через узкие репозитории и отображает сущности в DTO (контракт §3.3: логика
 * чтения — в сервисе, контроллер тонкий, репозитории без бизнес-логики). Транзакции —
 * только на чтение ({@code readOnly}). Курсор ленты — стабильный монотонный {@code id}
 * (или ключ «дата + id» при порядке «свежие первыми», §70).</p>
 *
 * <p><b>Персонализация ленты (§31).</b> Для вошедшего пользователя из выборки исключаются
 * скрытые им публикации (если не запрошено обратное), а каждый элемент помечается его
 * отношением к публикации. Резолв владельца и его маркеры вынесены в
 * {@link FeedPersonalization}, чтобы сервис не выходил за лимит зависимостей (§3.10) и не
 * смешивал чтение с персонализацией. Для анонимного запроса лента не меняется.</p>
 */
@Service
public class PostingQueryService {

    /** Размер страницы по умолчанию, если не задан клиентом. */
    static final int DEFAULT_LIMIT = 20;

    /** Максимальный размер страницы (защита от чрезмерной выборки). */
    static final int MAX_LIMIT = 100;

    private final PostingReadRepository postingRepository;
    private final PostingLanguageReadRepository languageRepository;
    private final PostingSkillReadRepository skillRepository;
    private final FeedPersonalization personalization;
    private final CoverageReadRepository coverageRepository;

    /**
     * @param postingRepository  публикации
     * @param languageRepository языковые требования публикации
     * @param skillRepository    требования-навыки публикации
     * @param personalization    персонализация ленты под вошедшего пользователя (§31)
     * @param coverageRepository оценки покрытия площадками (A5, §81)
     */
    public PostingQueryService(
            PostingReadRepository postingRepository,
            PostingLanguageReadRepository languageRepository,
            PostingSkillReadRepository skillRepository,
            FeedPersonalization personalization,
            CoverageReadRepository coverageRepository) {
        this.postingRepository = postingRepository;
        this.languageRepository = languageRepository;
        this.skillRepository = skillRepository;
        this.personalization = personalization;
        this.coverageRepository = coverageRepository;
    }

    /**
     * Возвращает страницу ленты публикаций, персонализированную под вошедшего пользователя.
     *
     * <p>Порядок {@code ID} — по возрастанию {@code id}, курсор — {@code id}; порядок
     * {@code POSTED} — свежие первыми (§70), курсор — ключ «дата + id», упакованный в число
     * ({@link PostedKeyset#toCursor}).</p>
     *
     * @param paging         курсор ({@code null} — с начала), размер страницы ({@code null} →
     *                       {@link #DEFAULT_LIMIT}, обрезается до {@link #MAX_LIMIT}) и порядок
     * @param filter         необязательные фильтры (поля {@code null} не применяются)
     * @param coverage       состояние покрытия (A5) или {@code null}; {@code SITE_ONLY} — «только скрытые»
     * @param authentication текущая аутентификация или {@code null} (анонимный запрос)
     * @param includeHidden  для вошедшего: включать ли скрытые им публикации (по умолчанию нет, §7.3)
     * @return элементы страницы и курсор следующей ({@code nextCursor = null} — страниц больше нет)
     */
    @Transactional(readOnly = true)
    public Page list(FeedPaging paging, PostingFilter filter, CoverageState coverage,
                     Authentication authentication, boolean includeHidden) {
        int size = pageSize(paging.limit());
        boolean byPosted = paging.sortOrDefault() == FeedPaging.Sort.POSTED;

        Long userId = personalization.currentUserId(authentication);
        Long hiddenForUserId = (userId != null && !includeHidden) ? userId : null;

        List<JobPosting> rows = byPosted
                ? postingRepository.searchByPosted(PostedKeyset.fromCursor(paging.cursor()),
                        filter, coverage, hiddenForUserId, Limit.of(size + 1))
                : postingRepository.search(paging.cursor() == null ? 0L : paging.cursor(),
                        filter, coverage, hiddenForUserId, Limit.of(size + 1));

        Long nextCursor = null;
        if (rows.size() > size) {
            JobPosting last = rows.get(size - 1);
            nextCursor = byPosted ? PostedKeyset.of(last).toCursor() : last.getId();
            rows = rows.subList(0, size);
        }

        List<Long> ids = rows.stream().map(JobPosting::getId).toList();
        Map<Long, SavedPosting> markers = personalization.markersByPostingId(userId, ids);
        Map<Long, Coverage> coverages = ids.isEmpty() ? Map.of()
                : coverageRepository.findByPosting_IdIn(ids).stream().collect(Collectors.toMap(
                        assessment -> assessment.getPosting().getId(), PostingQueryService::toCoverage));

        List<Summary> items = rows.stream()
                .map(posting -> toSummary(posting, markers.get(posting.getId()),
                        coverages.getOrDefault(posting.getId(), Coverage.NOT_CHECKED)))
                .toList();
        return new Page(items, nextCursor);
    }

    /**
     * Возвращает карточку публикации с языками и навыками.
     *
     * @param id идентификатор публикации
     * @return карточка или пустое значение, если публикации нет
     */
    @Transactional(readOnly = true)
    public Optional<Card> card(Long id) {
        return postingRepository.findById(id).map(this::toCard);
    }

    /** Приводит запрошенный размер страницы к допустимому диапазону. */
    private int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private Card toCard(JobPosting posting) {
        List<Language> languages = languageRepository
                .findByJobPosting_IdOrderByLanguageCodeAsc(posting.getId())
                .stream().map(PostingQueryService::toLanguage).toList();
        List<Skill> skills = skillRepository
                .findByJobPosting_IdOrderBySkillAsc(posting.getId())
                .stream().map(PostingQueryService::toSkill).toList();
        Coverage coverage = coverageRepository.findByPosting_Id(posting.getId())
                .map(PostingQueryService::toCoverage).orElse(Coverage.NOT_CHECKED);
        return new Card(head(posting), facts(posting), posting.getRawDescription(),
                new Requirements(languages, skills), coverage);
    }

    /**
     * Отображает публикацию в строку ленты с персональной пометкой. {@code marker} —
     * маркер вошедшего пользователя на этой публикации или {@code null} (аноним/без пометки).
     */
    private static Summary toSummary(JobPosting posting, SavedPosting marker, Coverage coverage) {
        Viewer viewer = marker == null
                ? new Viewer(null, false)
                : new Viewer(marker.getState(), marker.getSeenAt() != null);
        return new Summary(head(posting), facts(posting), viewer, coverage);
    }

    private static Coverage toCoverage(CoverageAssessment assessment) {
        return new Coverage(assessment.getState(), assessment.getCheckedPlatforms(), assessment.getReason(),
                assessment.getCheckedAt());
    }

    private static Head head(JobPosting posting) {
        return new Head(posting.getId(), posting.getExternalId(), posting.getRawTitle(), posting.getUrl());
    }

    /** Нормализованные сведения — одинаково для строки ленты и карточки. */
    private static Facts facts(JobPosting posting) {
        return new Facts(
                new Location(posting.getCity(), posting.getCountry(), posting.getWorkModality(),
                        posting.getRawLocation(), posting.getAdditionalLocations()),
                new Salary(posting.getSalaryMin(), posting.getSalaryMax(), posting.getSalaryCurrency(),
                        posting.getSalaryPeriod(), posting.getSalaryBasis()),
                new Experience(posting.getSeniority(), posting.getExperienceYearsMin()),
                new Timeline(posting.getPostedOn(), posting.getFirstSeenAt(), posting.getLastSeenAt(),
                        posting.getDetailFetchedAt()));
    }

    private static Language toLanguage(PostingLanguage language) {
        return new Language(language.getLanguageCode(), language.getMentioned(), language.getModality());
    }

    private static Skill toSkill(PostingSkill skill) {
        return new Skill(skill.getSkill(), skill.getStance(), skill.getModality());
    }
}
