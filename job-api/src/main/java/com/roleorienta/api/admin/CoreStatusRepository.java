package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.ReadStats;
import com.roleorienta.api.admin.AdminDtos.TaskStats;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Состояние ядра сейчас из {@code crawl_run} и {@code task} (пишет job-worker; §142) — только доступ к данным; тревоги
 * считает {@link CoreStatusService}.
 */
@Repository
public class CoreStatusRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public CoreStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return момент по часам БД — от него считаются сроки
     */
    public Instant now() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    /**
     * @return чтения источников: последнее успешное, успешные и отказы за час
     */
    public ReadStats reads() {
        return jdbcTemplate.queryForObject("""
                SELECT (SELECT max(finished_at) FROM crawl_run WHERE state <> 'FAILED') AS last_success_at,
                       count(*) FILTER (WHERE state <> 'FAILED') AS last_hour,
                       count(*) FILTER (WHERE state = 'FAILED') AS failed_last_hour
                FROM crawl_run WHERE finished_at >= now() - interval '1 hour'
                """, (row, number) -> new ReadStats(instant(row.getTimestamp("last_success_at")),
                row.getLong("last_hour"), row.getLong("failed_last_hour")));
    }

    /**
     * @return задания: в очереди, самое старое в очереди, неудачные за сутки
     */
    public TaskStats tasks() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FILTER (WHERE state = 'QUEUED') AS queued,
                       min(created_at) FILTER (WHERE state = 'QUEUED') AS oldest_queued_at,
                       count(*) FILTER (WHERE state = 'FAILED' AND updated_at >= now() - interval '1 day')
                           AS failed_last_day
                FROM task WHERE state IN ('QUEUED', 'FAILED')
                """, (row, number) -> new TaskStats(row.getLong("queued"),
                instant(row.getTimestamp("oldest_queued_at")), row.getLong("failed_last_day")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
