package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.MetricRow;
import com.roleorienta.api.admin.AdminDtos.MetricsTable;
import com.roleorienta.api.admin.AdminMetricsRepository.MetricValue;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Таблица показателей ядра для администратора (§132; бизнес-описание §7.3): строки — показатель и разрез (итог
 * первым), столбцы — последние дни с показателями, новые первыми; за день без строки — {@code null}. День,
 * записанный воркером между чтением дней и чтением значений, в таблицу не попадает (аудит §133).
 */
@Service
public class AdminMetricsService {

    /** Порядок показателей в таблице (технический документ §11); прочие — после них по алфавиту. */
    static final List<String> METRIC_ORDER = List.of("companies_known", "companies_processed", "companies_remaining",
            "companies_with_site", "companies_connected", "companies_failed", "vacancies_active", "vacancies_recheck",
            "vacancies_recognized", "vacancies_new", "vacancies_closed", "sources", "source_reads", "sources_overdue", "operations_failed");

    private final AdminMetricsRepository repository;

    /**
     * @param repository показатели ядра в БД
     */
    public AdminMetricsService(AdminMetricsRepository repository) {
        this.repository = repository;
    }

    /**
     * @param requested страна; {@code null} — первая страна с кодом ISO (иначе первая из имеющихся)
     * @param dayCount  сколько последних дней с показателями
     * @return таблица; показателей нет — пустые дни и строки
     */
    public MetricsTable table(String requested, int dayCount) {
        List<String> countries = repository.countries();
        String country = requested != null ? requested : defaultCountry(countries);
        List<LocalDate> days = repository.lastDays(country, dayCount);
        if (days.isEmpty()) {
            return new MetricsTable(country, countries, List.of(), List.of());
        }
        Map<LocalDate, Integer> column = new HashMap<>();
        days.forEach(day -> column.put(day, column.size()));
        Map<List<String>, Long[]> rows = new LinkedHashMap<>();
        for (MetricValue value : repository.values(country, days.get(days.size() - 1))) {
            Integer index = column.get(value.day());
            if (index != null) {
                rows.computeIfAbsent(List.of(value.metric(), value.dimension()), key -> new Long[days.size()])
                        [index] = value.value();
            }
        }
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
