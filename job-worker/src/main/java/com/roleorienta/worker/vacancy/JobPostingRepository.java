package com.roleorienta.worker.vacancy;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Доступ к публикациям. Запросы Spring Data строит по имени метода; {@link Query} — запрос JPQL
 * явно.
 */
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    /**
     * @param sourceId источник
     * @return все публикации источника
     */
    List<JobPosting> findBySourceId(Long sourceId);

    /**
     * @param vacancyIds вакансии
     * @return все публикации этих вакансий
     */
    List<JobPosting> findByVacancyIdIn(Collection<Long> vacancyIds);

    /**
     * @param sourceId источник
     * @return внешние id публикаций источника, текст которых уже получен
     */
    @Query("select p.externalId from JobPosting p where p.source.id = ?1 and p.content is not null")
    List<String> findExternalIdsWithContent(Long sourceId);

    /**
     * @param sourceId источник
     * @return внешние id незакрытых публикаций источника
     */
    @Query("select p.externalId from JobPosting p where p.source.id = ?1 and p.closedAt is null")
    List<String> findOpenExternalIds(Long sourceId);
}
