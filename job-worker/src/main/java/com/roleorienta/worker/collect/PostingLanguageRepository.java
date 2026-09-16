package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.PostingLanguage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к языковым требованиям публикации (§6, A07).
 */
public interface PostingLanguageRepository extends JpaRepository<PostingLanguage, Long> {

    /**
     * Удаляет все языковые строки публикации. Используется при повторном сборе перед
     * записью актуального набора языков (полная замена — идемпотентно относительно
     * содержимого текущего описания).
     *
     * @param jobPostingId идентификатор публикации
     */
    void deleteByJobPosting_Id(Long jobPostingId);
}
