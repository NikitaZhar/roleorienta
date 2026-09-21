package com.roleorienta.api.application;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к заметкам отклика. Владелец проверяется через владельца отклика (A23). */
public interface ApplicationNoteRepository extends JpaRepository<ApplicationNote, Long> {

    List<ApplicationNote> findByApplication_IdOrderByIdDesc(Long applicationId);
}
