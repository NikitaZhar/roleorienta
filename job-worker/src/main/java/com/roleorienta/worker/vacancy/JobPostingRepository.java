package com.roleorienta.worker.vacancy;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к публикациям. Запрос {@link #findBySourceIdAndExternalId} Spring Data строит по имени
 * метода.
 */
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    /**
     * @param sourceId   источник
     * @param externalId id публикации у источника
     * @return публикация, если уже известна
     */
    Optional<JobPosting> findBySourceIdAndExternalId(Long sourceId, String externalId);
}
