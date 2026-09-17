package com.roleorienta.api.posting;

import com.roleorienta.core.domain.PostingSkill;
import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * Доступ на чтение к требованиям-навыкам публикации для карточки (§7, A08).
 */
public interface PostingSkillReadRepository extends Repository<PostingSkill, Long> {

    /**
     * Навыки публикации, по каноническому имени (стабильный порядок выдачи).
     *
     * @param jobPostingId идентификатор публикации
     * @return навыки по возрастанию имени
     */
    List<PostingSkill> findByJobPosting_IdOrderBySkillAsc(Long jobPostingId);
}
