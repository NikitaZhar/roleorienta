package com.roleorienta.api.application;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к заметкам отклика. Владелец проверяется через владельца отклика (A23). */
public interface ApplicationNoteRepository extends JpaRepository<ApplicationNote, Long> {

    List<ApplicationNote> findByApplication_IdOrderByIdDesc(Long applicationId);

    Optional<ApplicationNote> findByIdAndApplication_IdAndApplication_User_Id(
            Long id, Long applicationId, Long userId);
}
