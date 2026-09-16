package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.PostingRevision;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к истории изменений публикаций ({@code posting_revision}). Создание строк —
 * через {@code save}.
 */
public interface PostingRevisionRepository extends JpaRepository<PostingRevision, Long> {
}
