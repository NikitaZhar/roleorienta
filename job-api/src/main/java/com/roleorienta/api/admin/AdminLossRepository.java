package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.LossCompany;
import com.roleorienta.api.admin.AdminDtos.LossCounts;
import com.roleorienta.api.admin.AdminDtos.LossSite;
import com.roleorienta.api.admin.AdminDtos.LossTitle;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Потери вакансий для администратора (§235; технический документ §15, MVP п. 5а): компании, у которых вакансии
 * портала и profesia.sk не найдены на собственной кадровой странице ({@code company_coverage}, пересчёт job-worker,
 * §234), и действия администратора по ним (JDBC).
 */
@Repository
public class AdminLossRepository {

    /** Сколько вакансий портала, которых нет на собственной странице, показывать в карточке. */
    static final int MISSING_SHOWN = 5;

    /** Провайдеры государственного портала и profesia.sk (job-worker {@code StatePortalAdapter}). */
    private static final String PORTAL_PROVIDERS = "('sluzbyzamestnanosti', 'profesia')";

    /**
     * Очередь потерь: найдено на собственной странице меньше, чем есть на портале и profesia.sk; без отметок «своей
     * страницы нет» и «не работодатель», не отложенные («Later») и без адреса кадровой страницы, который ещё
     * проверяется или добавлен после пересчёта (итог появится следующим пересчётом). Больше потерянных — первыми.
     */
    private static final String QUEUE = """
            FROM company_coverage cc JOIN company c ON c.id = cc.company_id
            WHERE cc.channel <> 'NO_OWN_PAGE' AND cc.found_on_own < cc.portal_titles
              AND NOT c.no_own_career_page AND NOT c.not_employer AND c.terminated_on IS NULL
              AND (c.loss_postponed_until IS NULL OR c.loss_postponed_until <= now())
              AND NOT EXISTS (SELECT 1 FROM company_request r WHERE r.company_id = c.id
                              AND (r.state = 'PENDING' OR r.created_at > cc.computed_at))""";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public AdminLossRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return первая компания очереди с числом компаний в очереди; пусто — потерь нет
     */
    public Optional<LossCompany> next() {
        return jdbcTemplate.query("""
                SELECT c.id, c.registration_number, c.name, cc.portal_titles, cc.found_on_own, cc.own_vacancies,
                       cc.channel, count(*) OVER () AS remaining
                """ + QUEUE + """
                 ORDER BY cc.portal_titles - cc.found_on_own DESC, cc.portal_titles DESC, c.id LIMIT 1
                """, (row, number) -> new LossCompany(row.getLong("id"), row.getString("registration_number"),
                row.getString("name"), new LossCounts(row.getInt("portal_titles"), row.getInt("found_on_own"),
                        row.getInt("own_vacancies"), row.getString("channel")), row.getLong("remaining")))
                .stream().findFirst();
    }

    /**
     * @param companyId компания
     * @return найденные сайты компании в порядке нахождения
     */
    public List<LossSite> sites(long companyId) {
        return jdbcTemplate.query("""
                SELECT host, check_result, career_url FROM company_site
                WHERE company_id = ? AND status = 'FOUND' ORDER BY id
                """, (row, number) -> new LossSite(row.getString("host"), row.getString("check_result"),
                row.getString("career_url")), companyId);
    }

    /**
     * Открытые вакансии портала и profesia.sk компании, которых нет среди открытых публикаций её собственных
     * источников (название — функцией {@code same_title}, правило §220, V90).
     *
     * @param companyId компания
     * @return до {@value #MISSING_SHOWN} разных названий по алфавиту, с адресом публикации
     */
    public List<LossTitle> missing(long companyId) {
        return jdbcTemplate.query("""
                SELECT min(p.title) AS title, min(p.url) AS url
                FROM company_source cs
                JOIN source s ON s.id = cs.source_id AND s.closed_at IS NULL AND s.provider IN %1$s
                JOIN job_posting p ON p.source_id = s.id AND p.closed_at IS NULL
                JOIN vacancy v ON v.id = p.vacancy_id AND v.state <> 'CLOSED'
                WHERE cs.company_id = ? AND cs.role = 'EMPLOYER'
                  AND NOT EXISTS (SELECT 1 FROM company_source os
                                  JOIN source o ON o.id = os.source_id AND o.closed_at IS NULL
                                                   AND o.provider NOT IN %1$s
                                  JOIN job_posting q ON q.source_id = o.id AND q.closed_at IS NULL
                                  WHERE os.company_id = cs.company_id AND os.role = 'EMPLOYER'
                                    AND same_title(q.title) = same_title(p.title))
                GROUP BY same_title(p.title) ORDER BY 1 LIMIT ?
                """.formatted(PORTAL_PROVIDERS), (row, number) -> new LossTitle(row.getString("title"),
                row.getString("url")), companyId, MISSING_SHOWN);
    }

    /**
     * @param companyId компания
     * @param duration  на сколько отложить
     * @return {@code false} — действующей компании нет
     */
    public boolean postpone(long companyId, Duration duration) {
        return jdbcTemplate.update("""
                UPDATE company SET loss_postponed_until = now() + make_interval(secs => ?)
                WHERE id = ? AND terminated_on IS NULL
                """, (double) duration.toSeconds(), companyId) == 1;
    }

    /**
     * @param companyId компания
     * @param noOwnPage своей кадровой страницы нет — вакансии с портала и profesia.sk
     * @return {@code false} — действующей компании нет
     */
    public boolean markNoOwnCareerPage(long companyId, boolean noOwnPage) {
        return jdbcTemplate.update("UPDATE company SET no_own_career_page = ? WHERE id = ? AND terminated_on IS NULL",
                noOwnPage, companyId) == 1;
    }
}
