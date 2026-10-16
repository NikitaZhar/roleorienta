package com.roleorienta.worker.delivery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Проходы и накопленные списки (JDBC; технический документ §7).
 */
@Repository
public class DeliveryRepository {

    /**
     * Порция: все подходящие вакансии, которых ещё нет в накопленном списке версии условий, в порядке потока (дата
     * первого обнаружения, затем id). Лимита нет (решение владельца 2026-10-15, стенограмма §120): после новых
     * условий видны сразу все подходящие вакансии, новые добавляются следующими проходами. Страна: среди определённых стран
     * вакансии есть страна пользователя или «*» (удалённо без ограничения страны, аудит §121); страна не определена —
     * вакансия не выдаётся (решение владельца 2026-10-15, стенограмма §120: в список Словакии попадали вакансии
     * Бангалора с нераспознанным местом). Формат не подходит, если он задан в условиях, указан у вакансии без противоречия и
     * отличается; гибрид подходит к «офису» (в условиях гибрида нет — решение владельца 2026-10-16, §124). Логика та же,
     * что {@code Fit.format}.
     */
    private static final String PORTION = """
            INSERT INTO delivered_vacancy (search_condition_id, vacancy_id, pass_run_id, delivered_at)
            SELECT c.id, v.id, ?, ?
            FROM search_condition c
            JOIN vacancy_position_match m ON m.position_id = c.position_id
            JOIN vacancy v ON v.id = m.vacancy_id
            WHERE c.id = ?
              AND v.state <> 'CLOSED'
              AND v.last_confirmed_at >= ?
              AND (v.work_countries && c.countries OR '*' = ANY (v.work_countries))
              AND (c.work_format IS NULL OR v.work_format IS NULL OR v.work_format IN ('CONFLICT', c.work_format)
                   OR c.work_format = 'OFFICE' AND v.work_format = 'HYBRID')
              AND NOT EXISTS (SELECT 1 FROM unsuitable_mark u WHERE u.user_id = c.user_id AND u.vacancy_id = v.id)
              AND NOT EXISTS (SELECT 1 FROM delivered_vacancy d
                              WHERE d.search_condition_id = c.id AND d.vacancy_id = v.id)
            ORDER BY v.first_seen_at, v.id
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public DeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Проход окна: создаётся, если его ещё нет.
     *
     * @param windowStart начало окна расписания
     * @return id прохода
     */
    public long passRun(Instant windowStart) {
        OffsetDateTime window = utc(windowStart);
        jdbcTemplate.update("INSERT INTO pass_run (window_start) VALUES (?) ON CONFLICT (window_start) DO NOTHING",
                window);
        return jdbcTemplate.queryForObject("SELECT id FROM pass_run WHERE window_start = ?", Long.class, window);
    }

    /**
     * @return активные версии условий
     */
    public List<Long> activeConditionIds() {
        return jdbcTemplate.queryForList("SELECT id FROM search_condition WHERE active ORDER BY id", Long.class);
    }

    /**
     * Выдаёт порцию версии условий по проходу одной транзакцией. Строка условий блокируется
     * ({@code FOR UPDATE}; смена условий берёт ту же блокировку); версия не активна — ничего; порция
     * этого прохода уже выдана (повтор задания) — ничего.
     *
     * @param conditionId  версия условий
     * @param passRunId    проход
     * @param now          момент выдачи
     * @param visibleSince не подтверждённые с этого момента вакансии не выдаются
     * @return число выданных вакансий
     */
    @Transactional
    public int deliverPortion(long conditionId, long passRunId, Instant now, Instant visibleSince) {
        List<Map<String, Object>> condition = jdbcTemplate.queryForList(
                "SELECT active FROM search_condition WHERE id = ? FOR UPDATE", conditionId);
        if (condition.isEmpty() || !Boolean.TRUE.equals(condition.get(0).get("active"))) {
            return 0;
        }
        Boolean delivered = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM delivered_vacancy WHERE search_condition_id = ? AND pass_run_id = ?)
                """, Boolean.class, conditionId, passRunId);
        if (Boolean.TRUE.equals(delivered)) {
            return 0;
        }
        return jdbcTemplate.update(PORTION, passRunId, utc(now), conditionId, utc(visibleSince));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
