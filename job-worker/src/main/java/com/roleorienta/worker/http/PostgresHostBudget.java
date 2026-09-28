package com.roleorienta.worker.http;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Бюджет запросов к хосту в PostgreSQL (технический документ §16.11): строка на хост со временем
 * следующего разрешённого запроса. Резервирование — один оператор {@code INSERT … ON CONFLICT DO
 * UPDATE}: строка хоста блокируется на время оператора, поэтому две реплики не получат одно место.
 * Время — часы БД ({@code now()}), общие для всех реплик.
 */
@Repository
public class PostgresHostBudget implements HostBudget {

    private static final double MILLIS_PER_SECOND = 1000.0;

    private final JdbcTemplate jdbcTemplate;
    private final PolitenessProperties properties;

    /**
     * @param jdbcTemplate доступ к БД
     * @param properties   промежуток между запросами и потолок ожидания
     */
    public PostgresHostBudget(JdbcTemplate jdbcTemplate, PolitenessProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * Место — {@code max(next_allowed_at, now())}; после него {@code next_allowed_at} сдвигается на
     * промежуток. Если место дальше потолка ожидания, строка не меняется и результат пуст.
     */
    @Override
    public Optional<Duration> reserve(String host) {
        double interval = seconds(properties.hostInterval());
        List<Double> waits = jdbcTemplate.queryForList("""
                INSERT INTO host_budget AS budget (host, next_allowed_at)
                VALUES (?, now() + make_interval(secs => ?))
                ON CONFLICT (host) DO UPDATE
                SET next_allowed_at = GREATEST(budget.next_allowed_at, now()) + make_interval(secs => ?)
                WHERE budget.next_allowed_at <= now() + make_interval(secs => ?)
                RETURNING GREATEST(EXTRACT(EPOCH FROM next_allowed_at - now()) - ?, 0)::float8
                """, Double.class, host, interval, interval, seconds(properties.hostMaxWait()), interval);
        return waits.stream().findFirst().map(wait -> Duration.ofMillis(Math.round(wait * MILLIS_PER_SECOND)));
    }

    @Override
    public void backOff(String host, Duration delay) {
        jdbcTemplate.update("""
                INSERT INTO host_budget AS budget (host, next_allowed_at)
                VALUES (?, now() + make_interval(secs => ?))
                ON CONFLICT (host) DO UPDATE
                SET next_allowed_at = GREATEST(budget.next_allowed_at, EXCLUDED.next_allowed_at)
                """, host, seconds(delay));
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / MILLIS_PER_SECOND;
    }
}
