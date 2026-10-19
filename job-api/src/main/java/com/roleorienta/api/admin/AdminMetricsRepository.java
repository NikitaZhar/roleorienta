package com.roleorienta.api.admin;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Чтение показателей ядра из {@code core_metric_daily} (пишет job-worker, стенограмма §131) — только доступ к данным;
 * таблицу для администратора собирает {@link AdminMetricsService} (§132, аудит §133).
 */
@Repository
public class AdminMetricsRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public AdminMetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return страны, за которые есть показатели, по алфавиту
     */
    public List<String> countries() {
        return jdbcTemplate.queryForList("SELECT DISTINCT country FROM core_metric_daily ORDER BY country",
                String.class);
    }

    /**
     * @param country страна
     * @param limit   сколько дней
     * @return последние дни с показателями страны, новые первыми
     */
    public List<LocalDate> lastDays(String country, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT day FROM core_metric_daily WHERE country = ? ORDER BY day DESC LIMIT ?",
                LocalDate.class, country, limit);
    }

    /**
     * @param country страна
     * @param from    первый день
     * @return значения показателей страны с дня {@code from}
     */
    public List<MetricValue> values(String country, LocalDate from) {
        return jdbcTemplate.query("""
                SELECT day, metric, dimension, value FROM core_metric_daily
                WHERE country = ? AND day >= ? ORDER BY metric, dimension, day""",
                (row, number) -> new MetricValue(row.getObject("day", LocalDate.class), row.getString("metric"),
                        row.getString("dimension"), row.getLong("value")), country, from);
    }

    /**
     * Значение показателя за день.
     *
     * @param day       день
     * @param metric    код показателя
     * @param dimension разрез; пусто — итог
     * @param value     значение
     */
    public record MetricValue(LocalDate day, String metric, String dimension, long value) {
    }
}
