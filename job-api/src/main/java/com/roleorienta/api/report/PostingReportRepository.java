package com.roleorienta.api.report;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к жалобам — всегда в пределах автора (A23). */
public interface PostingReportRepository extends JpaRepository<PostingReport, Long> {

    Optional<PostingReport> findByReporter_IdAndPosting_Id(Long userId, Long postingId);

    List<PostingReport> findByReporter_IdOrderByIdDesc(Long userId);

    List<PostingReport> findAllByOrderByIdDesc();

    List<PostingReport> findByStatusOrderByIdDesc(PostingReportStatus status);
}
