package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.LossAddress;
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
     * Очередь потерь (§235, §239) — только там, где помочь может администратор, и только после всех способов ядра
     * (решения владельца 2026-11-02): своя кадровая страница не найдена — сайта нет (поиск сайта по названию пройден),
     * на сайте страницы нет, сайт не открывается или запрещает чтение; все найденные сайты компании уже проверены (с
     * браузером без окна и переходом со страницы группы, §236, §237); второй и следующие сайты из Common Crawl ядро не
     * проверяет (§80) — их «не проверен» компанию не держит (§250). Страница найдена, но не читается или читается не
     * полностью — работа ядра (чтение любой кадровой страницы, п. 5а шаг 3), не администратора: в очередь не
     * попадает, видна в показателях. Без порога по числу вакансий; без отметок «своей страницы нет» и «не
     * работодатель», не отложенные («Later») и без адреса кадровой страницы, который ещё проверяется или добавлен после
     * пересчёта; последний добавленный адрес не подключён — компания не возвращается администратору по кругу: это
     * работа ядра (§243, решение владельца 2026-11-02). Больше потерянных — первыми.
     */
    private static final String QUEUE = """
            FROM company_coverage cc JOIN company c ON c.id = cc.company_id
            WHERE cc.channel IN ('NO_SITE', 'NO_CAREER_PAGE', 'UNREACHABLE', 'USE_FORBIDDEN')
              AND cc.found_on_own < cc.portal_titles
              AND (cc.channel <> 'NO_SITE' OR c.site_name_checked_at IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM company_site st WHERE st.company_id = c.id AND st.status = 'FOUND'
                              AND st.checked_at IS NULL
                              AND NOT (st.source = 'COMMON_CRAWL'
                                       AND EXISTS (SELECT 1 FROM company_site o WHERE o.company_id = c.id
                                                   AND o.status = 'FOUND' AND o.id <> st.id
                                                   AND (o.source <> 'COMMON_CRAWL' OR o.id < st.id))))
              AND NOT c.no_own_career_page AND NOT c.not_employer AND c.terminated_on IS NULL
              AND (c.loss_postponed_until IS NULL OR c.loss_postponed_until <= now())
              AND NOT EXISTS (SELECT 1 FROM company_request r WHERE r.company_id = c.id
                              AND (r.state = 'PENDING' OR r.created_at > cc.computed_at))
              AND (SELECT r.state FROM company_request r WHERE r.company_id = c.id
                   ORDER BY r.created_at DESC, r.id DESC LIMIT 1) IS DISTINCT FROM 'NOT_CONNECTED'""";

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
     * источников (название — функцией {@code same_title}, правило §220, V90); те же отборы, что у
     * {@code company_coverage}: вакансия не закрыта и в стране компании; страны ещё не посчитаны ({@code NULL}) или
     * не определены — вакансия берётся (аудит §244).
     *
     * @param companyId компания
     * @return до {@value #MISSING_SHOWN} разных названий по алфавиту, с адресом публикации
     */
    public List<LossTitle> missing(long companyId) {
        return jdbcTemplate.query("""
                SELECT min(p.title) AS title, min(p.url) AS url
                FROM company co
                JOIN company_source cs ON cs.company_id = co.id AND cs.role = 'EMPLOYER'
                JOIN source s ON s.id = cs.source_id AND s.closed_at IS NULL AND s.provider IN %1$s
                JOIN job_posting p ON p.source_id = s.id AND p.closed_at IS NULL
                JOIN vacancy v ON v.id = p.vacancy_id AND v.state <> 'CLOSED'
                                  AND (co.country = ANY (v.work_countries)
                                       OR coalesce(cardinality(v.work_countries), 0) = 0)
                WHERE co.id = ?
                  AND NOT EXISTS (SELECT 1 FROM company_source os
                                  JOIN source o ON o.id = os.source_id AND o.closed_at IS NULL
                                                   AND o.provider NOT IN %1$s
                                  JOIN job_posting q ON q.source_id = o.id AND q.closed_at IS NULL
                                  JOIN vacancy w ON w.id = q.vacancy_id AND w.state <> 'CLOSED'
                                                    AND (co.country = ANY (w.work_countries)
                                                         OR coalesce(cardinality(w.work_countries), 0) = 0)
                                  WHERE os.company_id = co.id AND os.role = 'EMPLOYER'
                                    AND same_title(q.title) = same_title(p.title))
                GROUP BY same_title(p.title) ORDER BY 1 LIMIT ?
                """.formatted(PORTAL_PROVIDERS), (row, number) -> new LossTitle(row.getString("title"),
                row.getString("url")), companyId, MISSING_SHOWN);
    }

    /**
     * @param companyId компания
     * @return последний добавленный адрес кадровой страницы и итог проверки; пусто — адресов нет
     */
    public Optional<LossAddress> lastAddress(long companyId) {
        return jdbcTemplate.query("""
                SELECT career_url, state, reason FROM company_request WHERE company_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, (row, number) -> new LossAddress(row.getString("career_url"), row.getString("state"),
                row.getString("reason")), companyId).stream().findFirst();
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
