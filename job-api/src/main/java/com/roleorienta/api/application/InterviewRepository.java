package com.roleorienta.api.application;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к собеседованиям — всегда через владельца отклика (A23). */
public interface InterviewRepository extends JpaRepository<Interview, Long> {

    List<Interview> findByApplication_IdOrderByScheduledAtAsc(Long applicationId);

    Optional<Interview> findByIdAndApplication_IdAndApplication_User_Id(
            Long id, Long applicationId, Long userId);
}
