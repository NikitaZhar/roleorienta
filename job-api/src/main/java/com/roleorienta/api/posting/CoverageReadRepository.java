package com.roleorienta.api.posting;

import com.roleorienta.core.domain.CoverageAssessment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Доступ на чтение к оценкам покрытия публикаций для ленты и карточки (A5, §81).
 */
public interface CoverageReadRepository extends Repository<CoverageAssessment, Long> {

    /**
     * Оценки покрытия публикаций страницы ленты — одним запросом (без N+1).
     *
     * @param postingIds идентификаторы публикаций
     * @return оценки тех публикаций, у которых они есть
     */
    List<CoverageAssessment> findByPosting_IdIn(Collection<Long> postingIds);

    /**
     * Оценка покрытия одной публикации.
     *
     * @param postingId идентификатор публикации
     * @return оценка или пусто — «не проверено»
     */
    Optional<CoverageAssessment> findByPosting_Id(Long postingId);
}
