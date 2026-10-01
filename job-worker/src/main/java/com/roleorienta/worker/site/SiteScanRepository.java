package com.roleorienta.worker.site;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Блоки скана Common Crawl и найденные сайты компаний (JDBC).
 */
@Repository
public class SiteScanRepository {

    private static final String COUNTRY = "SK";
    private static final String SOURCE = "COMMON_CRAWL";
    /** Назначение блоков скана сайтов (блоки обратного пути — {@code BOARD}). */
    private static final String PURPOSE = "SITE";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public SiteScanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return последний обход, чьи блоки индекса записаны; пусто — ни одного
     */
    public Optional<String> lastCrawl() {
        return jdbcTemplate.queryForList("SELECT max(crawl) FROM cc_index_block WHERE purpose = ?", String.class,
                PURPOSE).stream().filter(Objects::nonNull).findFirst();
    }

    /**
     * @param crawl обход
     * @return {@code true} — блоки обхода уже записаны
     */
    public boolean hasBlocks(String crawl) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cc_index_block WHERE crawl = ? AND purpose = ?)", Boolean.class, crawl,
                PURPOSE));
    }

    /**
     * Записывает блоки обхода; уже записанные (повтор задания) не меняются.
     *
     * @param crawl  обход
     * @param blocks блоки
     */
    @Transactional
    public void insertBlocks(String crawl, List<IndexBlock> blocks) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO cc_index_block (crawl, purpose, seq, file, block_offset, block_length)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, blocks, blocks.size(), (statement, block) -> {
                statement.setString(1, crawl);
                statement.setString(2, PURPOSE);
                statement.setInt(3, block.seq());
                statement.setString(4, block.file());
                statement.setLong(5, block.offset());
                statement.setInt(6, block.length());
            });
    }

    /**
     * @param crawl обход
     * @param limit сколько блоков
     * @return непросмотренные блоки по порядку
     */
    public List<IndexBlock> nextBlocks(String crawl, int limit) {
        return jdbcTemplate.query("""
                SELECT seq, file, block_offset, block_length FROM cc_index_block
                WHERE crawl = ? AND purpose = ? AND NOT done ORDER BY seq LIMIT ?
                """, (row, number) -> new IndexBlock(row.getInt("seq"), row.getString("file"),
                row.getLong("block_offset"), row.getInt("block_length")), crawl, PURPOSE, limit);
    }

    /**
     * @param numbers регистрационные номера
     * @return id действующих компаний Словакии по номеру
     */
    public Map<String, Long> activeCompanies(Set<String> numbers) {
        if (numbers.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>();
        jdbcTemplate.query("SELECT id, registration_number FROM company WHERE country = ? AND terminated_on IS NULL"
                + " AND registration_number IN (" + String.join(",", Collections.nCopies(numbers.size(), "?")) + ")",
                row -> {
                    ids.put(row.getString("registration_number"), row.getLong("id"));
                }, concat(COUNTRY, numbers));
        return ids;
    }

    /**
     * @return Словакия — активная страна сбора: скан ищет сайты словацких юрлиц по IČO на страницах
     *         {@code .sk}; страна, снятая всеми пользователями, не сканируется (бизнес-описание §4.1)
     */
    public boolean countryActive() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM collection_country WHERE country = ? AND active)", Boolean.class,
                COUNTRY));
    }

    /**
     * Находки блока и отметка «просмотрен» — одной транзакцией: после остановки блок
     * просматривается заново целиком, повторная находка не дублируется. У компании с найденным сайтом
     * снимается итог «сайт не найден» — новый итог даст проверка сайта.
     *
     * @param crawl   обход
     * @param seq     блок
     * @param matches найденные сайты
     */
    @Transactional
    public void completeBlock(String crawl, int seq, List<SiteMatch> matches) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO company_site (company_id, host, evidence_url, source, crawl)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (company_id, host) DO NOTHING
                """, matches, matches.size(), (statement, match) -> {
                statement.setLong(1, match.companyId());
                statement.setString(2, match.host());
                statement.setString(3, match.evidenceUrl());
                statement.setString(4, SOURCE);
                statement.setString(5, crawl);
            });
        jdbcTemplate.batchUpdate("DELETE FROM company_check WHERE company_id = ? AND result = 'SITE_NOT_FOUND'",
                matches, matches.size(), (statement, match) -> statement.setLong(1, match.companyId()));
        jdbcTemplate.update("UPDATE cc_index_block SET done = TRUE WHERE crawl = ? AND purpose = ? AND seq = ?", crawl,
                PURPOSE, seq);
    }

    /**
     * Скан обхода завершён: действующим компаниям без сайта и без итога — итог «сайт не найден»
     * (бизнес-описание §4.2). Повторный вызов ничего не меняет.
     *
     * @return сколько компаний получили итог
     */
    public int recordSitesNotFound() {
        return jdbcTemplate.update("""
                INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
                SELECT c.id, 'SITE_NOT_FOUND', now(), now() FROM company c
                WHERE c.terminated_on IS NULL AND NOT EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = c.id)
                ON CONFLICT (company_id) DO NOTHING
                """);
    }

    private static Object[] concat(String first, Set<String> rest) {
        Object[] values = new Object[rest.size() + 1];
        values[0] = first;
        int index = 1;
        for (String value : rest) {
            values[index++] = value;
        }
        return values;
    }

    /**
     * Сайт компании, подтверждённый её IČO на странице.
     *
     * @param companyId   компания
     * @param host        хост сайта
     * @param evidenceUrl страница с IČO
     */
    public record SiteMatch(long companyId, String host, String evidenceUrl) {
    }
}
