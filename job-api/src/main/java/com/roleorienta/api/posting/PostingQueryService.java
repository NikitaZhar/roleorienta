package com.roleorienta.api.posting;

import com.roleorienta.api.posting.PostingDtos.Card;
import com.roleorienta.api.posting.PostingDtos.Language;
import com.roleorienta.api.posting.PostingDtos.Page;
import com.roleorienta.api.posting.PostingDtos.Skill;
import com.roleorienta.api.posting.PostingDtos.Summary;
import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.PostingLanguage;
import com.roleorienta.core.domain.PostingSkill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Чтение публикаций для REST-выдачи (§7): лента с курсорной пагинацией и карточка.
 *
 * <p>Читает через узкие репозитории и отображает сущности в DTO (контракт §3.3: логика
 * чтения — в сервисе, контроллер тонкий, репозитории без бизнес-логики). Транзакции —
 * только на чтение ({@code readOnly}). Курсор ленты — стабильный монотонный {@code id};
 * фильтры и полнотекстовый поиск (§7.3) вводятся отдельными срезами.</p>
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

    /**
     * @param postingRepository  публикации
     * @param languageRepository языковые требования публикации
     * @param skillRepository    требования-навыки публикации
     */
    public PostingQueryService(
            PostingReadRepository postingRepository,
            PostingLanguageReadRepository languageRepository,
            PostingSkillReadRepository skillRepository) {
        this.postingRepository = postingRepository;
        this.languageRepository = languageRepository;
        this.skillRepository = skillRepository;
    }

    /**
     * Возвращает страницу ленты публикаций.
     *
     * @param cursor {@code id} последней публикации предыдущей страницы, либо {@code null} — с начала
     * @param limit  желаемый размер страницы; {@code null} → {@link #DEFAULT_LIMIT}, обрезается до {@link #MAX_LIMIT}
     * @param filter необязательные фильтры (поля {@code null} не применяются)
     * @return элементы страницы и курсор следующей ({@code nextCursor = null} — страниц больше нет)
     */
    @Transactional(readOnly = true)
    public Page list(Long cursor, Integer limit, PostingFilter filter) {
        int size = pageSize(limit);
        long after = cursor == null ? 0L : cursor;
        List<JobPosting> rows = postingRepository.search(after, filter, Limit.of(size + 1));

        Long nextCursor = null;
        if (rows.size() > size) {
            nextCursor = rows.get(size - 1).getId();
            rows = rows.subList(0, size);
        }
        List<Summary> items = rows.stream().map(PostingQueryService::toSummary).toList();
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
        return new Card(
                posting.getId(), posting.getExternalId(), posting.getRawTitle(), posting.getUrl(),
                posting.getRawLocation(), posting.getCity(), posting.getCountry(), posting.getWorkModality(),
                posting.getSalaryMin(), posting.getSalaryMax(), posting.getSalaryCurrency(),
                posting.getSalaryPeriod(), posting.getSalaryBasis(), posting.getSeniority(),
                posting.getExperienceYearsMin(), posting.getRawDescription(),
                posting.getFirstSeenAt(), posting.getLastSeenAt(), posting.getDetailFetchedAt(),
                languages, skills);
    }

    private static Summary toSummary(JobPosting posting) {
        return new Summary(
                posting.getId(), posting.getExternalId(), posting.getRawTitle(), posting.getUrl(),
                posting.getCity(), posting.getCountry(), posting.getWorkModality(),
                posting.getSalaryMin(), posting.getSalaryMax(), posting.getSalaryCurrency(),
                posting.getSeniority(), posting.getExperienceYearsMin(),
                posting.getFirstSeenAt(), posting.getLastSeenAt());
    }

    private static Language toLanguage(PostingLanguage language) {
        return new Language(language.getLanguageCode(), language.getMentioned(), language.getModality());
    }

    private static Skill toSkill(PostingSkill skill) {
        return new Skill(skill.getSkill(), skill.getStance(), skill.getModality());
    }
}
