package com.roleorienta.worker.digest;

import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.worker.digest.DigestContent.ChangedPosting;
import com.roleorienta.worker.digest.DigestContent.NewPosting;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Чтение данных дайджеста (A7, §88) — SQL, как у {@link SubscriptionLookup}: выборки идут через
 * несколько таблиц (подписки, связи с досками, публикации, оценки покрытия), сущности
 * пользователя в воркере нет.
 */
@Repository
public class DigestLookup {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к базе
     */
    public DigestLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Получатель и начало его следующего окна.
     *
     * @param userId      пользователь
     * @param email       адрес
     * @param windowStart конец последнего заведённого окна; если окон ещё не было — момент первой
     *                    подписки (baseline, A16: история до подписки не отправляется)
     */
    public record Recipient(Long userId, String email, Instant windowStart) {
    }

    /**
     * Пользователи с подписками на компании.
     *
     * @return получатели в порядке {@code id}
     */
    public List<Recipient> recipients() {
        return jdbcTemplate.query("""
                SELECT u.id, u.email,
                       coalesce((SELECT max(d.window_end) FROM digest_delivery d WHERE d.app_user_id = u.id),
                                min(s.created_at)) AS window_start
                FROM app_user u
                JOIN company_subscription s ON s.app_user_id = u.id
                GROUP BY u.id, u.email
                ORDER BY u.id
                """,
                (rs, rowNum) -> new Recipient(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()));
    }

    /**
     * Адрес пользователя.
     *
     * @param userId пользователь
     * @return адрес; пусто, если пользователя нет
     */
    public Optional<String> emailOf(Long userId) {
        return jdbcTemplate.queryForList("SELECT email FROM app_user WHERE id = ?", String.class, userId)
                .stream().findFirst();
    }

    /**
     * Содержимое дайджеста за окно {@code (from, to]}.
     *
     * @param userId пользователь
     * @param from   начало окна (не включая)
     * @param to     конец окна (включая)
     * @return новые вакансии компаний из подписок и изменения по уведомлениям пользователя
     */
    public DigestContent content(Long userId, Instant from, Instant to) {
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);
        List<NewPosting> newPostings = jdbcTemplate.query("""
                SELECT jp.raw_title, jp.url, min(c.name), coalesce(min(ca.state), 'UNKNOWN')
                FROM company_subscription s
                JOIN company c ON c.id = s.company_id
                JOIN company_source cs ON cs.company_id = s.company_id
                JOIN job_posting jp ON jp.source_id = cs.source_id
                LEFT JOIN coverage_assessment ca ON ca.job_posting_id = jp.id
                WHERE s.app_user_id = ? AND jp.first_seen_at > ? AND jp.first_seen_at <= ?
                GROUP BY jp.id, jp.raw_title, jp.url
                ORDER BY coalesce(min(ca.state), 'UNKNOWN') <> 'SITE_ONLY', min(c.name), jp.raw_title, jp.id
                """,
                (rs, rowNum) -> new NewPosting(rs.getString(1), rs.getString(2), rs.getString(3),
                        CoverageState.valueOf(rs.getString(4))),
                userId, start, end);
        List<ChangedPosting> changes = jdbcTemplate.query("""
                SELECT jp.raw_title, jp.url, min(c.name), string_agg(DISTINCT n.field_name, ',')
                FROM notification n
                JOIN job_posting jp ON jp.id = n.job_posting_id
                JOIN company c ON c.id = n.company_id
                WHERE n.app_user_id = ? AND n.created_at > ? AND n.created_at <= ?
                  AND NOT (jp.first_seen_at > ? AND jp.first_seen_at <= ?)
                GROUP BY jp.id, jp.raw_title, jp.url
                ORDER BY min(c.name), jp.raw_title, jp.id
                """,
                (rs, rowNum) -> new ChangedPosting(rs.getString(1), rs.getString(2), rs.getString(3),
                        Arrays.asList(rs.getString(4).split(","))),
                userId, start, end, start, end);
        return new DigestContent(newPostings, changes);
    }
}
