package com.roleorienta.api.posting;

import com.roleorienta.core.domain.LanguageMention;
import com.roleorienta.core.domain.LanguageModality;
import com.roleorienta.core.domain.RequirementModality;
import com.roleorienta.core.domain.SalaryBasis;
import com.roleorienta.core.domain.SalaryPeriod;
import com.roleorienta.core.domain.SeniorityLevel;
import com.roleorienta.core.domain.SkillStance;
import com.roleorienta.core.domain.WorkModality;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTO выдачи публикаций (§7): только данные, без логики (контракт §3.3). Вложенные
 * записи — чтобы не плодить файлы под каждый мелкий тип (минимизация §3.10). Перечисления
 * сериализуются именами (напр. {@code "REMOTE"}, {@code "NEGATED"}).
 */
public final class PostingDtos {

    private PostingDtos() {
    }

    /** Строка ленты — компактный набор полей публикации. */
    public record Summary(
            Long id,
            String externalId,
            String title,
            String url,
            String city,
            String country,
            WorkModality workModality,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String salaryCurrency,
            SeniorityLevel seniority,
            Integer experienceYearsMin,
            Instant firstSeenAt,
            Instant lastSeenAt) {
    }

    /** Языковое требование в карточке (A07): факт упоминания и обязательность раздельно. */
    public record Language(String languageCode, LanguageMention mentioned, LanguageModality modality) {
    }

    /** Требование-навык в карточке (A08): отношение и обязательность раздельно. */
    public record Skill(String skill, SkillStance stance, RequirementModality modality) {
    }

    /** Карточка публикации — полный набор нормализованных полей и требований. */
    public record Card(
            Long id,
            String externalId,
            String title,
            String url,
            String rawLocation,
            String city,
            String country,
            WorkModality workModality,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String salaryCurrency,
            SalaryPeriod salaryPeriod,
            SalaryBasis salaryBasis,
            SeniorityLevel seniority,
            Integer experienceYearsMin,
            String description,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant detailFetchedAt,
            List<Language> languages,
            List<Skill> skills) {
    }

    /** Страница ленты: элементы и курсор следующей страницы ({@code null} — конец). */
    public record Page(List<Summary> items, Long nextCursor) {
    }
}
