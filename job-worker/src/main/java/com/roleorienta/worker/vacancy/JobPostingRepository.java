package com.roleorienta.worker.vacancy;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к публикациям. Запросы Spring Data строит по имени метода.
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
}
