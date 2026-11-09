package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Склейка уже записанных вакансий по ссылке на вакансию у работодателя (§268): объявление площадки (profesia.sk в
 * общем списке портала или добор с profesia.sk), чья кнопка отклика ведёт на страницу публикации системы найма
 * работодателя ({@code employer_link_key} ссылки из кнопки = ключ ссылки публикации), — та же вакансия: она
 * присоединяется к вакансии этой публикации. Склейка при записи (§139, §260) этого не делает: ссылка из кнопки
 * становится известна после записи объявления, а доска системы найма бывает подключена к компании позже, чем записаны
 * её вакансии (Accenture: доска Workday с ранних срезов, связь с компанией — §266).
 *
 * <p>Остаётся вакансия публикации системы найма; на неё переносятся публикации, история, выдача, сводка на почту и
 * отметки пользователя — как в склейке накопленных дублей V92 (из двух отметок одного пользователя — дальше по пути
 * отклика: Employer declined &gt; Interview &gt; Applied &gt; Not interested &gt; Viewed, затем последняя); соответствие
 * позициям и требования к языку пересчитывает сопоставление ({@code match_version} сброшен).</p>
 *
 * <p>Склеивается, только если ключ ведёт ровно на одну открытую публикацию не с площадки (аудит §268, §270): ключ,
 * общий для нескольких публикаций (одна ссылка у разных вакансий доски), — не основание. Так же исключены цепочки
 * между проходами: у вакансии-приёмника уже есть эта публикация, а другой с тем же ключом нет. И только при том же
 * названии ({@code same_title}, без пометок пола и зарплаты; §270): кнопки разных объявлений одного работодателя
 * бывают направлены на одну общую страницу вакансий («Farmaceut/ka — Kremnica», «— Senec» → одна страница). Одна транзакция;
 * {@code EmployerLinkSources} вызывает склейку отдельно от подключения досок — сбой склейки их не откатывает. Склейка
 * берёт исключительную блокировку записи вакансий ({@link PostgresLeaderLock#VACANCY_WRITE}, §271): запись чтения
 * источника, выдача и сводка на почту не идут одновременно с ней; строки публикаций и вакансий блокируются в порядке
 * id.</p>
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
     * @return сколько вакансий присоединено к вакансиям систем найма
     */
    @Transactional
    public int mergeByEmployerLinks() {
        // запись публикаций, выдача и сводка ждут конца склейки, склейка — конца идущих (§271)
        jdbcTemplate.queryForObject(PostgresLeaderLock.VACANCY_WRITE_EXCLUSIVE, Integer.class);
        // временные таблицы удаляются при завершении транзакции; в одной внешней транзакции — повторный вызов
        jdbcTemplate.execute("DROP TABLE IF EXISTS merge_map, merge_status");
        jdbcTemplate.execute("""
                CREATE TEMPORARY TABLE merge_map ON COMMIT DROP AS
                WITH link AS (
                    SELECT p.vacancy_id, employer_link_key(p.employer_url) AS link_key, same_title(p.title) AS title_key
                    FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id AND v.state <> 'CLOSED'
                    WHERE p.closed_at IS NULL AND p.employer_url IS NOT NULL
                    UNION
                    SELECT p.vacancy_id, employer_link_key(o.employer_url), same_title(p.title)
                    FROM profesia_offer o
                    JOIN job_posting p ON p.external_id LIKE 'profesia:%' AND p.external_id = 'profesia:' || o.num
                                      AND p.closed_at IS NULL
                    JOIN vacancy v ON v.id = p.vacancy_id AND v.state <> 'CLOSED'
                    WHERE o.employer_url IS NOT NULL),
                target AS (
                    SELECT vacancy_id, link_key, title_key FROM (
                        SELECT p.vacancy_id, employer_link_key(p.url) AS link_key, same_title(p.title) AS title_key,
                               count(*) OVER (PARTITION BY employer_link_key(p.url)) AS same_key
                        FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id AND v.state <> 'CLOSED'
                        WHERE p.closed_at IS NULL AND p.external_id NOT LIKE 'profesia:%') t
                    WHERE same_key = 1),
                pair AS (
                    SELECT l.vacancy_id, min(t.vacancy_id) AS keeper_id
                    FROM link l JOIN target t ON t.link_key = l.link_key AND t.title_key = l.title_key
                                             AND t.title_key <> '' AND t.vacancy_id <> l.vacancy_id
                    GROUP BY l.vacancy_id)
                SELECT vacancy_id, keeper_id FROM pair
                WHERE vacancy_id NOT IN (SELECT keeper_id FROM pair)""");
        int merged = jdbcTemplate.queryForObject("SELECT count(*) FROM merge_map", Integer.class);
        if (merged == 0) {
            return 0;
        }
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
        jdbcTemplate.update("DELETE FROM vacancy_position_match WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map)");
        jdbcTemplate.update("DELETE FROM vacancy_language WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map)");
        jdbcTemplate.update("DELETE FROM vacancy WHERE id IN (SELECT vacancy_id FROM merge_map)");
        return merged;
    }
}
