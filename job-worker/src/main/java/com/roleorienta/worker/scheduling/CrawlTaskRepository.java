package com.roleorienta.worker.scheduling;

import com.roleorienta.core.domain.CrawlTask;
import org.springframework.data.jpa.repository.JpaRepository;
import com.roleorienta.core.domain.CrawlTaskType;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к фоновым заданиям для планировщика. Создание — через {@code save}
 * (идентификатор задания нужен для outbox-события).
 */
public interface CrawlTaskRepository extends JpaRepository<CrawlTask, Long> {

    /**
     * Сколько заданий данного типа создано по источнику с указанного момента — для
     * дневного бюджета деталей (§63).
     *
     * @param type     тип задания
     * @param sourceId источник
     * @param since    начало окна
     * @return число заданий
     */
    @Query("SELECT count(t) FROM CrawlTask t WHERE t.type = :type AND t.crawlRun.source.id = :sourceId "
            + "AND t.createdAt >= :since")
    long countByTypeForSourceSince(@Param("type") CrawlTaskType type, @Param("sourceId") Long sourceId,
                                   @Param("since") Instant since);
}
