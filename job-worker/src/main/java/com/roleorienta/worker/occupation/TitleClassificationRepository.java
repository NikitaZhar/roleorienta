package com.roleorienta.worker.occupation;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Названия к распознаванию моделью и её ответы ({@code title_classification}, V93; стенограмма §262). Ключ названия —
 * {@code same_title} (V92): одинаковые названия разных вакансий спрашиваются один раз.
 */
@Repository
public class TitleClassificationRepository {

    /**
     * Ключ открытой публикации, позиции которой нет: ни кода портала, ни распознанной группы, ни позиции словаря у её
     * вакансии — и ключа ещё нет среди ответов модели.
     */
    private static final String UNRECOGNIZED = """
            FROM job_posting p
            WHERE p.closed_at IS NULL AND p.occupation_code IS NULL AND p.title_occupation_code IS NULL
              AND NOT EXISTS (SELECT 1 FROM vacancy_position_match m WHERE m.vacancy_id = p.vacancy_id)
              AND same_title(p.title) <> ''
              AND NOT EXISTS (SELECT 1 FROM title_classification c WHERE c.title_key = same_title(p.title))""";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public TitleClassificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return каталог: группы ISCO-08 уровня 4 с английским названием, по коду
     */
    public List<TitleClassifier.Group> groups() {
        return jdbcTemplate.query("""
                SELECT g.code, n.name FROM occupation_group g
                JOIN occupation_group_name n ON n.code = g.code AND n.language = 'en'
                WHERE g.level = 4 ORDER BY g.code
                """, (row, number) -> new TitleClassifier.Group(row.getString(1), row.getString(2)));
    }

    /**
     * Названия без позиции: по одному названию на ключ, сначала ключи, общие для большего числа открытых публикаций
     * (больше вакансий станет видно за один ответ).
     *
     * @param limit сколько
     * @return названия, как в публикации
     */
    public List<String> unrecognized(int limit) {
        return jdbcTemplate.queryForList("SELECT min(p.title) " + UNRECOGNIZED
                + " GROUP BY same_title(p.title) ORDER BY count(*) DESC, min(p.title) LIMIT ?", String.class, limit);
    }

    /**
     * Названия вакансий портала с кодом профессии (§203), ключа которых ещё нет среди ответов: ответ модели на них
     * сверяется с кодом портала — мера точности. Выбираются по кругу ключей ({@code md5}), а не по частоте: выборка
     * — разные профессии, не только самые частые.
     *
     * @param limit сколько
     * @return названия, как в публикации
     */
    public List<String> coded(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT min(p.title) FROM job_posting p
                WHERE p.occupation_code IS NOT NULL AND same_title(p.title) <> ''
                  AND NOT EXISTS (SELECT 1 FROM title_classification c WHERE c.title_key = same_title(p.title))
                GROUP BY same_title(p.title) ORDER BY md5(same_title(p.title)) LIMIT ?
                """, String.class, limit);
    }

    /**
     * Записывает ответы и сразу проставляет распознанную группу открытым публикациям без кода портала и без
     * распознанной группы с теми же ключами — одной транзакцией (выдача видит их без ожидания пересчёта раз в сутки,
     * §215; пересчёт держит их тем же правилом — {@link TitleOccupationRepository#relearn}).
     *
     * @param answers название → код группы (пусто — модель не распознала)
     * @param model   какая модель ответила
     * @return у скольких публикаций появилась распознанная группа
     */
    @Transactional
    public int save(Map<String, String> answers, String model) {
        answers.forEach((title, code) -> jdbcTemplate.update("""
                INSERT INTO title_classification (title_key, code, model) VALUES (same_title(?), ?, ?)
                ON CONFLICT (title_key) DO NOTHING
                """, title, code.isEmpty() ? null : code, model));
        return jdbcTemplate.update("""
                UPDATE job_posting p SET title_occupation_code = c.code
                FROM title_classification c
                WHERE c.code IS NOT NULL AND c.title_key = same_title(p.title)
                  AND p.closed_at IS NULL AND p.occupation_code IS NULL AND p.title_occupation_code IS NULL
                  AND c.title_key = ANY (SELECT same_title(t) FROM unnest(CAST(? AS TEXT[])) t)
                """, (PreparedStatement statement) -> statement.setArray(1,
                        statement.getConnection().createArrayOf("text", answers.keySet().toArray())));
    }
}
