package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Пересмотр уже записанных вакансий по одному определению «та же вакансия» (docs/vacancy-identity-plan.md §4, §5,
 * срез 3; стенограмма §280; заменяет склейку только по кнопке отклика §268, §270). Пары открытых вакансий:
 *
 * <ol>
 *   <li>{@code profesia_number} — публикации с тем же номером объявления profesia.sk (любые источники);</li>
 *   <li>{@code address} — тот же адрес без сессионных параметров;</li>
 *   <li>{@code board_id} — тот же провайдер, хост и внешний id; у Workday — и без суффикса копии {@code -N} при том же
 *       ключе названия;</li>
 *   <li>{@code button} — кнопка отклика ведёт ровно на одну открытую публикацию не с площадки с тем же ключом
 *       названия (правило §270);</li>
 *   <li>{@code button_pair} — кнопки двух публикаций ведут на один адрес, ключи названий равны, места не разные;</li>
 *   <li>{@code title_place} — у работодателя (компания с ролью {@code EMPLOYER}, у объявления добора — компания
 *       объявления) то же название и место: места пересекаются, или место у одной общее при двух вакансиях этого
 *       названия у работодателя ({@code vacancy_places_relation}, V103). Запрет: вакансия, у публикации которой кнопка
 *       ведёт на открытую публикацию с другим названием (ZF Levice), по названию не склеивается.</li>
 * </ol>
 *
 * <p>Пары «не склеивать» ({@code vacancy_merge_block}) пропускаются. Вакансия присоединяется к меньшему id пары
 * (признак — первый по порядку выше); цепочки — следующей партией. На оставшуюся переносятся публикации, история,
 * выдача, сводка на почту и отметки пользователя (из двух — дальше по пути отклика: Employer declined &gt; Interview &gt;
 * Applied &gt; Not interested &gt; Viewed, затем последняя), как в V92 и §268; название и ссылка оставшейся — от главной
 * публикации (первая открытая не с profesia.sk, нет такой — первая); сопоставление пересчитывается
 * ({@code match_version} сброшен). До переноса в журнал ({@code vacancy_merge}) пишутся признак, строка присоединённой
 * вакансии, её публикации, отметки, история, выдача и сводка — для разделения ({@code ops/vacancy-unmerge.sql}).</p>
 *
 * <p>Партия — не больше заданного числа присоединяемых вакансий, своя транзакция с исключительной блокировкой записи
 * вакансий ({@link PostgresLeaderLock#VACANCY_WRITE}, §271): запись чтений, выдача и сводка ждут её, она — их
 * (ожидание — не дольше {@code lock_timeout}, §277). Пары считаются заново в каждой партии — по всем открытым
 * вакансиям, ключи публикаций — вычисляемые колонки V102 (отступление от плана §5 «дельтой»: полный подсчёт пар —
 * секунды, а дельта требовала бы признака изменения публикации, которого в схеме нет).</p>
 */
@Repository
public class VacancyMergeRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public VacancyMergeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param limit сколько вакансий присоединить за партию, не больше
     * @return сколько присоединено; меньше {@code limit} — пар больше нет (кроме цепочек, которые разрешит следующий
     *         пересмотр)
     */
    @Transactional
    public int mergeBatch(int limit) {
        // запись публикаций, выдача и сводка ждут конца партии, партия — конца идущих (§271)
        jdbcTemplate.queryForObject(PostgresLeaderLock.VACANCY_WRITE_EXCLUSIVE, Integer.class);
        // временные таблицы удаляются при завершении транзакции; в одной внешней транзакции — повторный вызов
        jdbcTemplate.execute("DROP TABLE IF EXISTS merge_posting, merge_employer, merge_pair, merge_map, merge_status");
        jdbcTemplate.execute("""
                CREATE TEMPORARY TABLE merge_posting ON COMMIT DROP AS
                SELECT p.id, p.vacancy_id, p.source_id, s.provider, p.external_id, p.title_key, p.place_keys,
                       p.profesia_num, p.address_key, employer_link_key(p.url) AS link_key,
                       employer_link_key(coalesce(p.employer_url, o.employer_url)) AS button_key,
                       split_part(split_part(regexp_replace(lower(p.url), '^[a-z]+://(www[.])*', ''), '/', 1),
                                  chr(63), 1) AS host
                FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id AND v.state <> 'CLOSED'
                JOIN source s ON s.id = p.source_id
                LEFT JOIN profesia_offer o ON p.external_id LIKE 'profesia:%' AND 'profesia:' || o.num = p.external_id
                WHERE p.closed_at IS NULL""");
        jdbcTemplate.execute("""
                CREATE TEMPORARY TABLE merge_employer ON COMMIT DROP AS
                SELECT DISTINCT mp.vacancy_id, e.company_id, mp.title_key
                FROM merge_posting mp
                JOIN LATERAL (SELECT cs.company_id FROM company_source cs
                              WHERE cs.source_id = mp.source_id AND cs.role = 'EMPLOYER'
                              UNION
                              SELECT o.company_id FROM profesia_offer o
                              WHERE mp.external_id = 'profesia:' || o.num AND o.company_id IS NOT NULL) e ON TRUE
                WHERE mp.title_key <> ''""");
        jdbcTemplate.execute("""
                CREATE TEMPORARY TABLE merge_pair ON COMMIT DROP AS
                WITH target AS (
                    SELECT link_key, min(vacancy_id) AS vacancy_id, min(title_key) AS title_key
                    FROM merge_posting WHERE external_id NOT LIKE 'profesia:%'
                    GROUP BY link_key HAVING count(*) = 1),
                vacancy_places AS (
                    SELECT mp.vacancy_id, array_remove(array_agg(DISTINCT place), NULL) AS places
                    FROM merge_posting mp LEFT JOIN LATERAL unnest(mp.place_keys) place ON TRUE
                    GROUP BY mp.vacancy_id),
                vetoed AS (
                    SELECT DISTINCT a.vacancy_id FROM merge_posting a
                    JOIN merge_posting t ON t.link_key = a.button_key AND t.title_key <> a.title_key),
                titled AS (
                    SELECT company_id, title_key, count(DISTINCT vacancy_id) AS vacancies
                    FROM merge_employer GROUP BY company_id, title_key)
                SELECT a.vacancy_id, b.vacancy_id AS other_id, 'profesia_number' AS signal, 1 AS rank
                FROM merge_posting a JOIN merge_posting b ON b.profesia_num = a.profesia_num AND b.vacancy_id <> a.vacancy_id
                UNION ALL
                SELECT a.vacancy_id, b.vacancy_id, 'address', 2
                FROM merge_posting a JOIN merge_posting b ON b.address_key = a.address_key AND b.vacancy_id <> a.vacancy_id
                UNION ALL
                SELECT a.vacancy_id, b.vacancy_id, 'board_id', 3
                FROM merge_posting a JOIN merge_posting b
                     ON b.provider = a.provider AND b.host = a.host AND b.vacancy_id <> a.vacancy_id
                        AND regexp_replace(b.external_id, '-[0-9]+$', '') = regexp_replace(a.external_id, '-[0-9]+$', '')
                WHERE b.external_id = a.external_id OR a.provider = 'workday' AND b.title_key = a.title_key
                UNION ALL
                SELECT a.vacancy_id, t.vacancy_id, 'button', 4
                FROM merge_posting a JOIN target t ON t.link_key = a.button_key AND t.title_key = a.title_key
                WHERE t.vacancy_id <> a.vacancy_id AND a.title_key <> ''
                UNION ALL
                SELECT a.vacancy_id, b.vacancy_id, 'button_pair', 5
                FROM merge_posting a JOIN merge_posting b
                     ON b.button_key = a.button_key AND b.title_key = a.title_key AND b.vacancy_id <> a.vacancy_id
                WHERE a.title_key <> '' AND vacancy_places_relation(a.place_keys, b.place_keys) <> 'different'
                UNION ALL
                SELECT x.vacancy_id, y.vacancy_id, 'title_place', 6
                FROM merge_employer x
                JOIN merge_employer y ON y.company_id = x.company_id AND y.title_key = x.title_key
                                     AND y.vacancy_id <> x.vacancy_id
                JOIN titled n ON n.company_id = x.company_id AND n.title_key = x.title_key
                JOIN vacancy_places px ON px.vacancy_id = x.vacancy_id
                JOIN vacancy_places py ON py.vacancy_id = y.vacancy_id
                WHERE x.vacancy_id NOT IN (SELECT vacancy_id FROM vetoed)
                  AND y.vacancy_id NOT IN (SELECT vacancy_id FROM vetoed)
                  AND (vacancy_places_relation(px.places, py.places) = 'same'
                       OR vacancy_places_relation(px.places, py.places) = 'loose' AND n.vacancies = 2)""");
        // пара — в обе стороны: признак кнопки даёт только «объявление -> вакансия по кнопке», а присоединение — к меньшему
        // id; без обратной строки объявление, записанное раньше вакансии системы найма, не склеивалось бы
        jdbcTemplate.update("INSERT INTO merge_pair SELECT other_id, vacancy_id, signal, rank FROM merge_pair");
        jdbcTemplate.update("""
                DELETE FROM merge_pair m USING vacancy_merge_block b
                WHERE b.vacancy_a = least(m.vacancy_id, m.other_id) AND b.vacancy_b = greatest(m.vacancy_id, m.other_id)""");
        jdbcTemplate.execute("""
                CREATE TEMPORARY TABLE merge_map ON COMMIT DROP AS
                SELECT DISTINCT ON (vacancy_id) vacancy_id, other_id AS keeper_id, signal
                FROM merge_pair WHERE other_id < vacancy_id
                ORDER BY vacancy_id, other_id, rank""");
        // присоединяемая не бывает приёмником в той же партии: цепочка разрешится следующей партией
        jdbcTemplate.update("DELETE FROM merge_map WHERE vacancy_id IN (SELECT keeper_id FROM merge_map)");
        jdbcTemplate.update("DELETE FROM merge_map WHERE vacancy_id NOT IN (SELECT vacancy_id FROM merge_map "
                + "ORDER BY vacancy_id LIMIT ?)", limit);
        int merged = jdbcTemplate.queryForObject("SELECT count(*) FROM merge_map", Integer.class);
        if (merged == 0) {
            return 0;
        }
        journal();
        move();
        return merged;
    }

    /** Журнал склеек до переноса: всё, что нужно для разделения. */
    private void journal() {
        jdbcTemplate.update("""
                INSERT INTO vacancy_merge (kept_vacancy_id, merged_vacancy_id, signal, merged_vacancy, postings,
                                           status_history, user_marks, delivered, digest)
                SELECT m.keeper_id, m.vacancy_id, m.signal, to_jsonb(v),
                       ARRAY(SELECT p.id FROM job_posting p WHERE p.vacancy_id = m.vacancy_id ORDER BY p.id),
                       ARRAY(SELECT h.id FROM vacancy_status_history h WHERE h.vacancy_id = m.vacancy_id ORDER BY h.id),
                       (SELECT jsonb_agg(to_jsonb(s)) FROM vacancy_user_status s WHERE s.vacancy_id = m.vacancy_id),
                       (SELECT jsonb_agg(to_jsonb(d)) FROM delivered_vacancy d WHERE d.vacancy_id = m.vacancy_id),
                       (SELECT jsonb_agg(to_jsonb(g)) FROM digest_vacancy g WHERE g.vacancy_id = m.vacancy_id)
                FROM merge_map m JOIN vacancy v ON v.id = m.vacancy_id""");
    }

    /** Перенос на оставшиеся вакансии (как V92, §268) и удаление присоединённых. */
    private void move() {
        // строки — в порядке id, как пересчёт профессий и запись (аудит §222): встречные обновления не ждут друг друга
        jdbcTemplate.queryForList("SELECT id FROM job_posting WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map) "
                + "ORDER BY id FOR UPDATE");
        jdbcTemplate.queryForList("SELECT id FROM vacancy WHERE id IN (SELECT vacancy_id FROM merge_map UNION "
                + "SELECT keeper_id FROM merge_map) ORDER BY id FOR UPDATE");
        jdbcTemplate.update("UPDATE job_posting p SET vacancy_id = m.keeper_id FROM merge_map m "
                + "WHERE p.vacancy_id = m.vacancy_id");
        jdbcTemplate.update("UPDATE vacancy_revision r SET vacancy_id = m.keeper_id FROM merge_map m "
                + "WHERE r.vacancy_id = m.vacancy_id");
        jdbcTemplate.update("UPDATE vacancy_status_history h SET vacancy_id = m.keeper_id FROM merge_map m "
                + "WHERE h.vacancy_id = m.vacancy_id");
        jdbcTemplate.update("""
                INSERT INTO delivered_vacancy (search_condition_id, vacancy_id, pass_run_id, delivered_at)
                SELECT d.search_condition_id, m.keeper_id, min(d.pass_run_id), min(d.delivered_at)
                FROM delivered_vacancy d JOIN merge_map m ON m.vacancy_id = d.vacancy_id
                GROUP BY d.search_condition_id, m.keeper_id
                ON CONFLICT (search_condition_id, vacancy_id)
                    DO UPDATE SET pass_run_id = CASE WHEN EXCLUDED.delivered_at < delivered_vacancy.delivered_at
                                                     THEN EXCLUDED.pass_run_id ELSE delivered_vacancy.pass_run_id END,
                                  delivered_at = least(delivered_vacancy.delivered_at, EXCLUDED.delivered_at)""");
        jdbcTemplate.update("DELETE FROM delivered_vacancy WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map)");
        jdbcTemplate.update("""
                INSERT INTO digest_vacancy (user_id, vacancy_id, mailed_at)
                SELECT d.user_id, m.keeper_id, min(d.mailed_at)
                FROM digest_vacancy d JOIN merge_map m ON m.vacancy_id = d.vacancy_id
                GROUP BY d.user_id, m.keeper_id
                ON CONFLICT (user_id, vacancy_id) DO NOTHING""");
        jdbcTemplate.update("DELETE FROM digest_vacancy WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map)");
        jdbcTemplate.execute("""
                CREATE TEMPORARY TABLE merge_status ON COMMIT DROP AS
                SELECT DISTINCT ON (s.user_id, coalesce(m.keeper_id, s.vacancy_id))
                       s.user_id, coalesce(m.keeper_id, s.vacancy_id) AS vacancy_id, s.status, s.changed_at
                FROM vacancy_user_status s
                LEFT JOIN merge_map m ON m.vacancy_id = s.vacancy_id
                WHERE s.vacancy_id IN (SELECT vacancy_id FROM merge_map UNION SELECT keeper_id FROM merge_map)
                ORDER BY s.user_id, coalesce(m.keeper_id, s.vacancy_id),
                         CASE s.status WHEN 'REJECTED' THEN 5 WHEN 'INTERVIEW' THEN 4 WHEN 'APPLIED' THEN 3
                                       WHEN 'NOT_INTERESTED' THEN 2 ELSE 1 END DESC,
                         s.changed_at DESC""");
        jdbcTemplate.update("DELETE FROM vacancy_user_status "
                + "WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map UNION SELECT keeper_id FROM merge_map)");
        jdbcTemplate.update("INSERT INTO vacancy_user_status (user_id, vacancy_id, status, changed_at) "
                + "SELECT user_id, vacancy_id, status, changed_at FROM merge_status");
        jdbcTemplate.update("""
                UPDATE vacancy k SET posted_at = least(k.posted_at, g.posted_at),
                                     first_seen_at = least(k.first_seen_at, g.first_seen_at),
                                     last_confirmed_at = greatest(k.last_confirmed_at, g.last_confirmed_at),
                                     state = CASE WHEN g.active THEN 'ACTIVE' ELSE k.state END,
                                     match_version = NULL
                FROM (SELECT m.keeper_id, min(v.posted_at) AS posted_at, min(v.first_seen_at) AS first_seen_at,
                             max(v.last_confirmed_at) AS last_confirmed_at, bool_or(v.state = 'ACTIVE') AS active
                      FROM merge_map m JOIN vacancy v ON v.id = m.vacancy_id GROUP BY m.keeper_id) g
                WHERE k.id = g.keeper_id""");
        // название и ссылка — от главной публикации: первая открытая не с profesia.sk, нет такой — первая (§271)
        jdbcTemplate.update("""
                UPDATE vacancy k SET title = main.title, primary_url = main.url
                FROM (SELECT DISTINCT ON (p.vacancy_id) p.vacancy_id, p.title, p.url
                      FROM job_posting p
                      WHERE p.closed_at IS NULL AND p.vacancy_id IN (SELECT keeper_id FROM merge_map)
                      ORDER BY p.vacancy_id, p.external_id LIKE 'profesia:%', p.id) main
                WHERE k.id = main.vacancy_id""");
        jdbcTemplate.update("DELETE FROM vacancy_position_match WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map)");
        jdbcTemplate.update("DELETE FROM vacancy_language WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map)");
        jdbcTemplate.update("DELETE FROM vacancy WHERE id IN (SELECT vacancy_id FROM merge_map)");
    }
}
