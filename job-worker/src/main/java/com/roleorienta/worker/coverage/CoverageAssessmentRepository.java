package com.roleorienta.worker.coverage;

import com.roleorienta.core.domain.CoverageAssessment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Оценки покрытия: одна на публикацию (A5, §82). */
public interface CoverageAssessmentRepository extends JpaRepository<CoverageAssessment, Long> {

    /**
     * Оценка публикации.
     *
     * @param postingId идентификатор публикации
     * @return оценка или пусто
     */
    Optional<CoverageAssessment> findByPosting_Id(Long postingId);
}
