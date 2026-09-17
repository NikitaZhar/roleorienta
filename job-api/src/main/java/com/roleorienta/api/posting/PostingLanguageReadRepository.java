package com.roleorienta.api.posting;

import com.roleorienta.core.domain.PostingLanguage;
import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * Доступ на чтение к языковым требованиям публикации для карточки (§7, A07).
 */
public interface PostingLanguageReadRepository extends Repository<PostingLanguage, Long> {

    /**
     * Языковые требования публикации, по коду языка (стабильный порядок выдачи).
     *
     * @param jobPostingId идентификатор публикации
     * @return языковые требования по возрастанию кода языка
     */
    List<PostingLanguage> findByJobPosting_IdOrderByLanguageCodeAsc(Long jobPostingId);
}
