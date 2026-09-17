package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.PostingLanguage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к языковым требованиям публикации (§6, A07).
 */
public interface PostingLanguageRepository extends JpaRepository<PostingLanguage, Long> {

    /**
     * Удаляет все языковые строки публикации перед записью актуального набора (полная
     * замена при повторном сборе).
     *
     * <p>Реализовано <b>bulk-запросом</b>, а не производным {@code deleteBy...},
     * намеренно: bulk-{@code DELETE} выполняется немедленно, до последующих
     * {@code INSERT}. У {@link PostingLanguage} ключ — {@code IDENTITY}, поэтому
     * {@code save(...)} вставляет строку сразу (Hibernate нужен сгенерированный id), а
     * производное удаление откладывается до flush — тогда в одной транзакции вставка
     * прошла бы раньше удаления и повторный сбор падал бы на уникальном ключе
     * {@code (job_posting_id, language_code)}. Немедленный bulk-{@code DELETE} делает
     * «полную замену» идемпотентной при повторной обработке (§23).</p>
     *
     * @param jobPostingId идентификатор публикации
     */
    @Modifying
    @Query("delete from PostingLanguage pl where pl.jobPosting.id = :jobPostingId")
    void deleteByJobPosting_Id(@Param("jobPostingId") Long jobPostingId);
}
