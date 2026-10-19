package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.MetricRow;
import com.roleorienta.api.admin.AdminDtos.MetricsTable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Показатели ядра из {@code core_metric_daily} (пишет job-worker, стенограмма §131) — таблицей для администратора
 * (§132): строки — показатель и разрез (итог первым), столбцы — последние дни с показателями, новые первыми.
 */
@Repository
public class AdminMetricsRepository {

    /** Порядок показателей в таблице (технический документ §11); прочие — после них по алфавиту. */
    static final List<String> METRIC_ORDER = List.of("companies_known", "companies_processed", "companies_remaining",
            "companies_with_site", "companies_connected", "companies_failed", "vacancies_active", "vacancies_recheck",
            "vacancies_new", "vacancies_closed", "sources", "source_reads", "sources_overdue", "operations_failed");

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public AdminMetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param requested страна; {@code null} — первая страна с кодом ISO (иначе первая из имеющихся)
     * @param dayCount  сколько последних дней с показателями
     * @return таблица; показателей нет — пустые дни и строки
     */
    public MetricsTable table(String requested, int dayCount) {
        List<String> countries = jdbcTemplate.queryForList(
                "SELECT DISTINCT country FROM core_metric_daily ORDER BY country", String.class);
        String country = requested != null ? requested : defaultCountry(countries);
        List<LocalDate> days = jdbcTemplate.queryForList(
                "SELECT DISTINCT day FROM core_metric_daily WHERE country = ? ORDER BY day DESC LIMIT ?",
                LocalDate.class, country, dayCount);
        if (days.isEmpty()) {
            return new MetricsTable(country, countries, List.of(), List.of());
        }
        Map<LocalDate, Integer> column = new HashMap<>();
        days.forEach(day -> column.put(day, column.size()));
        Map<List<String>, Long[]> rows = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT day, metric, dimension, value FROM core_metric_daily
                WHERE country = ? AND day >= ? ORDER BY metric, dimension""",
                row -> {
                    Integer index = column.get(row.getObject("day", LocalDate.class));
                    if (index != null) {
                        rows.computeIfAbsent(List.of(row.getString("metric"), row.getString("dimension")),
                                key -> new Long[days.size()])[index] = row.getLong("value");
                    }
                }, country, days.get(days.size() - 1));
        List<MetricRow> ordered = new ArrayList<>();
        rows.forEach((key, values) -> ordered.add(new MetricRow(key.get(0), key.get(1), Arrays.asList(values))));
        ordered.sort(Comparator.comparingInt((MetricRow row) -> rank(row.metric())).thenComparing(MetricRow::metric)
                .thenComparing(MetricRow::dimension));
        return new MetricsTable(country, countries, days.stream().map(LocalDate::toString).toList(), ordered);
    }

    private static int rank(String metric) {
        int index = METRIC_ORDER.indexOf(metric);
        return index < 0 ? METRIC_ORDER.size() : index;
    }

    private static String defaultCountry(List<String> countries) {
        return countries.stream().filter(code -> code.length() == 2).findFirst()
                .orElse(countries.isEmpty() ? "" : countries.get(0));
    }
}
