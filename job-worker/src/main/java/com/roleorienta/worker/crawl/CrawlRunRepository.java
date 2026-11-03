package com.roleorienta.worker.crawl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к обходам.
 */
public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {

    /**
     * @param sourceId источник
     * @param reads    сколько последних обходов источника смотреть
     * @return сколько из них — неполные из-за обвала числа публикаций ({@link PartialReason#COUNT_DROP}, §257)
     */
    @Query(value = """
            SELECT count(*) FROM (SELECT partial_reason FROM crawl_run WHERE source_id = :sourceId
                                  ORDER BY id DESC LIMIT :reads) last
            WHERE partial_reason = 'COUNT_DROP'""", nativeQuery = true)
    long countDropsAmongLast(@Param("sourceId") long sourceId, @Param("reads") int reads);
}
