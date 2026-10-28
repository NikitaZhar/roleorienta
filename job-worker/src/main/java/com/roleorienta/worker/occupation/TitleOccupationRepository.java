package com.roleorienta.worker.occupation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Профессия по названию вакансии (§215): обучение на публикациях с кодом государственного портала и распознавание у
 * остальных (JDBC; правило «головы» названия — функция БД {@code title_head}, V84).
 */
@Repository
public class TitleOccupationRepository {

    /** Наименьшее число публикаций портала с одной головой, по которым она выучивается. */
    static final int MIN_POSTINGS = 2;

    /** Наименьшая доля одной группы среди публикаций головы. */
    static final double MIN_SHARE = 0.7;

    /** Самая короткая выучиваемая голова: короче — сокращения («tpp»). */
    static final int MIN_HEAD = 3;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public TitleOccupationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Переучивает и перераспознаёт одной транзакцией: выученные головы заменяются целиком; у открытых публикаций без
     * кода портала распознанная группа — выученная голова, иначе однозначный синоним ESCO (у названия одной профессии
     * ESCO — одна группа, у одинаковых названий разных профессий — одна и та же), иначе нет. Меняются только
     * отличающиеся значения; у закрытых публикаций распознанная группа не трогается; у публикаций, получивших код
     * портала после распознавания, распознанная снимается (аудит §222: иначе вакансия подходила бы и чужой группе).
     * Строки блокируются по порядку id ({@code FOR UPDATE}), запись публикаций упорядочивает изменения так же
     * (Hibernate {@code order_updates}) — риск взаимной блокировки снижен (аудит §222); случившаяся прерывает
     * транзакцию, задание повторяется.
     *
     * @return сколько голов выучено и у скольких публикаций распознанная группа изменилась
     */
    @Transactional
    public Result relearn() {
        jdbcTemplate.update("DELETE FROM title_occupation");
        int learned = jdbcTemplate.update("""
                INSERT INTO title_occupation (head, code, postings)
                SELECT DISTINCT ON (head) head, code, total
                FROM (SELECT title_head(p.title) AS head, p.occupation_code AS code, count(*) AS n,
                             sum(count(*)) OVER (PARTITION BY title_head(p.title)) AS total
                      FROM job_posting p
                      JOIN occupation_group g ON g.code = p.occupation_code AND g.level = 4
                      WHERE p.occupation_code IS NOT NULL
                      GROUP BY 1, 2) counted
                WHERE length(head) >= ? AND length(head) <= 500 AND total >= ? AND n >= ? * total
                ORDER BY head, n DESC
                """, MIN_HEAD, MIN_POSTINGS, MIN_SHARE);
        int changed = jdbcTemplate.update("""
                WITH esco AS (
                    SELECT title_head(l.label) AS head, min(e.group_code) AS code
                    FROM esco_label l JOIN esco_occupation e ON e.id = l.occupation_id
                    GROUP BY 1 HAVING count(DISTINCT e.group_code) = 1 AND length(title_head(l.label)) >= ?),
                recognized AS (
                    SELECT p.id, coalesce(t.code, esco.code) AS code
                    FROM job_posting p
                    LEFT JOIN title_occupation t ON t.head = title_head(p.title)
                    LEFT JOIN esco ON esco.head = title_head(p.title)
                    WHERE p.occupation_code IS NULL AND p.closed_at IS NULL
                      AND p.title_occupation_code IS DISTINCT FROM coalesce(t.code, esco.code)
                    ORDER BY p.id
                    FOR UPDATE OF p)
                UPDATE job_posting p SET title_occupation_code = r.code
                FROM recognized r
                WHERE p.id = r.id
                """, MIN_HEAD);
        int superseded = jdbcTemplate.update("""
                WITH coded AS (
                    SELECT id FROM job_posting
                    WHERE occupation_code IS NOT NULL AND title_occupation_code IS NOT NULL
                    ORDER BY id
                    FOR UPDATE)
                UPDATE job_posting p SET title_occupation_code = NULL FROM coded WHERE p.id = coded.id
                """);
        return new Result(learned, changed + superseded);
    }

    /**
     * Итог пересчёта.
     *
     * @param learned выучено голов
     * @param changed публикаций с изменившейся распознанной группой
     */
    public record Result(int learned, int changed) {
    }
}
