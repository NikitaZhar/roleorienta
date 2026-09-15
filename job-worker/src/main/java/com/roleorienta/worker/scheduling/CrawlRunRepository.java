package com.roleorienta.worker.scheduling;

import com.roleorienta.core.domain.CrawlRun;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к обходам источников для планировщика.
 */
public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {

    /**
     * Атомарно создаёт обход для источника в окне, если его ещё нет. Конфликт по
     * уникальному ключу {@code (source_id, window_start)} гасится
     * {@code ON CONFLICT DO NOTHING}: повторный тик в том же окне ничего не пишет.
     * Документация:
     * https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT
     *
     * @param sourceId    идентификатор источника
     * @param windowStart начало окна расписания (UTC)
     * @param state       начальное состояние обхода
     * @return 1, если обход создан впервые; 0, если он уже существовал
     */
    @Modifying
    @Query(value = "INSERT INTO crawl_run (source_id, window_start, state) "
            + "VALUES (:sourceId, :windowStart, :state) "
            + "ON CONFLICT ON CONSTRAINT uq_crawl_run_source_window DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("sourceId") Long sourceId,
                       @Param("windowStart") Instant windowStart,
                       @Param("state") String state);

    /**
     * Возвращает идентификатор обхода источника в заданном окне.
     *
     * @param sourceId    идентификатор источника
     * @param windowStart начало окна расписания (UTC)
     * @return id обхода
     */
    @Query(value = "SELECT id FROM crawl_run WHERE source_id = :sourceId AND window_start = :windowStart",
            nativeQuery = true)
    Long findId(@Param("sourceId") Long sourceId, @Param("windowStart") Instant windowStart);
}
