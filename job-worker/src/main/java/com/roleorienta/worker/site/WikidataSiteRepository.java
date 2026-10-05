package com.roleorienta.worker.site;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Данные шага «Wikidata» поиска сайта: основание использования, компании к проверке, запись находки.
 */
@Repository
public class WikidataSiteRepository {

    /** Провайдер в реестре оснований ({@code source_permission}). */
    static final String PROVIDER = "wikidata";
    private static final String SOURCE = "WIKIDATA";
    private static final String COUNTRY = "SK";
    /** Номеров в одном условии {@code IN} — далеко от предела параметров PostgreSQL. */
    private static final int CHUNK = 5000;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public WikidataSiteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return есть ли записанное разрешение на использование Wikidata (технический документ §10)
     */
    public boolean permitted() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM source_permission
                               WHERE scope = 'PROVIDER' AND provider = ? AND decision = 'ALLOW')
                """, Boolean.class, PROVIDER));
    }

    /**
     * Компании к проверке: действующие компании Словакии — активной страны сбора — с номером из Wikidata, без
     * найденного сайта и без записи сайта из Wikidata (проверенная компания второй раз не берётся).
     *
     * @param numbers IČO из Wikidata
     * @param limit   не больше
     * @return компании по возрастанию id
     */
    public List<DueCompany> dueCompanies(Set<String> numbers, int limit) {
        List<String> all = new ArrayList<>(numbers);
        List<DueCompany> due = new ArrayList<>();
        for (int from = 0; from < all.size() && due.size() < limit; from += CHUNK) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
            List<Object> arguments = new ArrayList<>(chunk);
            arguments.add(0, COUNTRY);
            arguments.add(limit - due.size());
            due.addAll(jdbcTemplate.query("""
                    SELECT c.id, c.registration_number FROM company c
                    JOIN collection_country cc ON cc.country = c.country AND cc.active
                    WHERE c.country = ? AND c.terminated_on IS NULL
                      AND c.registration_number IN (%s)
                      AND NOT EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = c.id
                                      AND (s.status = 'FOUND' OR s.source = 'WIKIDATA'))
                    ORDER BY c.id LIMIT ?
                    """.formatted(String.join(",", Collections.nCopies(chunk.size(), "?"))),
                    (row, number) -> new DueCompany(row.getLong(1), row.getString(2)), arguments.toArray()));
        }
        return due;
    }

    /**
     * Сайт из Wikidata одной транзакцией; найденный сайт снимает итог «сайт не найден» — новый итог даст
     * проверка сайта. Повторная запись того же хоста ничего не меняет.
     *
     * @param companyId компания
     * @param host      хост сайта
     * @param item      адрес элемента Wikidata — доказательство
     * @param proof     {@code WIKIDATA} или {@code WIKIDATA_403}
     * @param found     {@code true} — находка, {@code false} — кандидат (сайт не открылся)
     */
    @Transactional
    public void record(long companyId, String host, String item, String proof, boolean found) {
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, proof, status)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (company_id, host) DO NOTHING
                """, companyId, host, item, SOURCE, proof, found ? "FOUND" : "CANDIDATE");
        if (found) {
            jdbcTemplate.update("DELETE FROM company_check WHERE company_id = ? AND result = 'SITE_NOT_FOUND'",
                    companyId);
        }
    }

    /**
     * Компания к проверке.
     *
     * @param id                 компания
     * @param registrationNumber IČO
     */
    public record DueCompany(long id, String registrationNumber) {
    }
}
