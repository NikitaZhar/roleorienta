package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.PostingSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к требованиям-навыкам публикации (§6, A08).
 */
public interface PostingSkillRepository extends JpaRepository<PostingSkill, Long> {

    /**
     * Удаляет все строки-навыки публикации перед записью актуального набора (полная
     * замена при повторном сборе).
     *
     * <p>Bulk-запрос (а не производное {@code deleteBy...}) — по той же причине, что и в
     * {@link PostingLanguageRepository#deleteByJobPosting_Id}: {@code DELETE} должен
     * выполниться немедленно, до {@code INSERT} новых строк, иначе при повторной
     * обработке (ключ {@code IDENTITY}, {@code save()} вставляет сразу) сбор падал бы на
     * уникальном ключе {@code (job_posting_id, skill)} (§23).</p>
     *
     * @param jobPostingId идентификатор публикации
     */
    @Modifying
    @Query("delete from PostingSkill ps where ps.jobPosting.id = :jobPostingId")
    void deleteByJobPosting_Id(@Param("jobPostingId") Long jobPostingId);
}
