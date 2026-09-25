package com.roleorienta.worker.coverage;

import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.JobPosting;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Что проверять на площадке (A5, §82): работодатели и их публикации в стране площадки.
 */
public interface CoverageTargetRepository extends Repository<Company, Long> {

    /**
     * Работодатели, у которых есть публикации в стране без свежей оценки покрытия
     * (нет оценки или она старше {@code freshAfter}), по возрастанию {@code id}.
     *
     * @param country    страна площадки
     * @param freshAfter оценки не раньше этого момента считаются свежими
     * @param limit      бюджет прохода
     * @return работодатели к проверке
     */
    @Query("""
            select c from Company c where exists (
                select 1 from CompanySource cs, JobPosting p
                where cs.company = c and p.source = cs.source and p.country = :country
                  and not exists (select 1 from CoverageAssessment ca
                                  where ca.posting = p and ca.checkedAt >= :freshAfter))
            order by c.id
            """)
    List<Company> companiesDue(@Param("country") String country, @Param("freshAfter") Instant freshAfter,
                               Limit limit);

    /**
     * Публикации работодателя в стране площадки — вместе с доской ({@code join fetch}: имя сайта
     * нужно для бренда доски, §86, без отдельного запроса на каждую публикацию).
     *
     * @param company работодатель
     * @param country страна площадки
     * @return публикации
     */
    @Query("""
            select p from JobPosting p join fetch p.source where p.country = :country and exists (
                select 1 from CompanySource cs where cs.source = p.source and cs.company = :company)
            """)
    List<JobPosting> postingsOf(@Param("company") Company company, @Param("country") String country);
}
