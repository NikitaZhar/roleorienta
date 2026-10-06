package com.roleorienta.worker.site;

import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Данные шагов 3–4 поиска сайта (адрес по названию): компании к проверке и запись итога.
 */
@Repository
public class SiteNameRepository {

    private static final String SOURCE = "NAME";
    private static final String COUNTRY = "SK";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public SiteNameRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Компании к проверке: действующие компании Словакии — активной страны сбора — с признаком найма (у компании
     * есть источник вакансий: портал или доска системы найма; решение владельца, §48), без найденного сайта, не
     * проверенные по названию или проверенные давнее срока. Сначала не проверенные.
     *
     * @param limit        не больше
     * @param recheckAfter срок до повторной проверки
     * @return компании с IČO и названием
     */
    public List<DueCompany> dueCompanies(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT c.id, c.registration_number, c.name FROM company c
                JOIN collection_country cc ON cc.country = c.country AND cc.active
                WHERE c.country = ? AND c.terminated_on IS NULL
                  AND EXISTS (SELECT 1 FROM company_source cs WHERE cs.company_id = c.id)
                  AND NOT EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = c.id AND s.status = 'FOUND')
                  AND (c.site_name_checked_at IS NULL
                       OR c.site_name_checked_at < now() - make_interval(secs => ?))
                ORDER BY c.site_name_checked_at NULLS FIRST, c.id
                LIMIT ?
                """, (row, number) -> new DueCompany(row.getLong(1), row.getString(2), row.getString(3)),
                COUNTRY, (double) recheckAfter.toSeconds(), limit);
    }

    /**
     * @param companyId компания
     * @param domain    домен без {@code www.}
     * @return есть ли у компании сайт на этом домене из другого источника (находка или кандидат)
     */
    public boolean hasDomain(long companyId, String domain) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM company_site
                               WHERE company_id = ? AND source <> ? AND regexp_replace(host, '^www\\.', '') = ?)
                """, Boolean.class, companyId, SOURCE, domain));
    }

    /**
     * Итог проверки компании одной транзакцией: отметка «проверяли»; сайты записываются с источником
     * {@code NAME} (повтор того же хоста ничего не меняет); находка снимает итог «сайт не найден» — новый итог
     * даст проверка сайта.
     *
     * @param companyId компания
     * @param sites     находка или кандидаты; пусто — ничего не найдено
     */
    @Transactional
    public void record(long companyId, List<NameSite> sites) {
        jdbcTemplate.update("UPDATE company SET site_name_checked_at = now() WHERE id = ?", companyId);
        for (NameSite site : sites) {
            jdbcTemplate.update("""
                    INSERT INTO company_site (company_id, host, start_url, evidence_url, source, proof, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (company_id, host) DO NOTHING
                    """, companyId, site.host(), site.startUrl(), site.evidenceUrl(), SOURCE, site.proof(),
                    site.found() ? "FOUND" : "CANDIDATE");
        }
        if (sites.stream().anyMatch(NameSite::found)) {
            jdbcTemplate.update("DELETE FROM company_check WHERE company_id = ? AND result = 'SITE_NOT_FOUND'",
                    companyId);
        }
    }

    /**
     * Компания к проверке.
     *
     * @param id                 компания
     * @param registrationNumber IČO
     * @param name               название в реестре
     */
    public record DueCompany(long id, String registrationNumber, String name) {
    }

    /**
     * Сайт по названию.
     *
     * @param host        хост
     * @param startUrl    адрес стартовой страницы с путём; {@code null} — главная
     * @param evidenceUrl проверенный адрес
     * @param proof       способ подтверждения ({@code company_site.proof})
     * @param found       находка ({@code true}) или кандидат
     */
    public record NameSite(String host, String startUrl, String evidenceUrl, String proof, boolean found) {
    }
}
