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
    /** Способ подтверждения: IČO компании на странице сайта. */
    private static final String PROOF = "REGISTRATION_NUMBER";
    /** Назначение блоков скана сайтов (блоки обратного пути — {@code BOARD}). */
    private static final String PURPOSE = "SITE";

    /**
     * Сайт с тем же хостом, найденный другим способом (Wikidata, портал, адрес по названию), снимает отказ
     * {@code REJECTED} правила Common Crawl (§86): подтверждение другим источником сильнее. Дописывается к
     * {@code INSERT INTO company_site … ON CONFLICT (company_id, host)} вместо {@code DO NOTHING}.
     */
    public static final String RESTORE_REJECTED = """
            DO UPDATE SET status = EXCLUDED.status, source = EXCLUDED.source, proof = EXCLUDED.proof,
                start_url = coalesce(EXCLUDED.start_url, company_site.start_url), checked_at = NULL
            WHERE company_site.status = 'REJECTED'""";

    /**
     * Правило Common Crawl (решение владельца §75; стенограмма §86): сайт компании из Common Crawl засчитывается
     * ({@code FOUND}), если это её единственный сайт из Common Crawl или в первой части его хоста (без {@code www.})
     * есть бренд названия — первое слово {@code company.name_slug} от трёх букв; иначе — не засчитан
     * ({@code REJECTED}: чужие интернет-магазины с IČO перевозчика в условиях доставки). Сайты из Common Crawl,
     * подтверждённые потом другим способом (proof не {@code REGISTRATION_NUMBER}), правило не трогает. Параметр —
     * компания; результат — сколько сайтов сменили состояние.
     */
    private static final String REVIEW_COMMON_CRAWL = """
            UPDATE company_site s
            SET status = CASE WHEN cc.sites = 1 OR cc.brand THEN 'FOUND' ELSE 'REJECTED' END
            FROM (SELECT o.id, count(*) OVER () AS sites,
                         length(split_part(c.name_slug, '-', 1)) >= 3
                         AND split_part(regexp_replace(o.host, '^www\\.', ''), '.', 1)
                             LIKE '%' || split_part(c.name_slug, '-', 1) || '%' AS brand
                  FROM company_site o JOIN company c ON c.id = o.company_id
                  WHERE o.company_id = ? AND o.source = 'COMMON_CRAWL' AND o.proof = 'REGISTRATION_NUMBER'
                    AND o.status IN ('FOUND', 'REJECTED')) cc
            WHERE s.id = cc.id AND s.status <> CASE WHEN cc.sites = 1 OR cc.brand THEN 'FOUND' ELSE 'REJECTED' END
            """;

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
     * просматривается заново целиком, повторная находка не дублируется. Затем у каждой компании блока — правило
     * Common Crawl ({@link #REVIEW_COMMON_CRAWL}): сменился итог правила — её найденные сайты перепроверяются
     * (итог компании пересчитает проверка кадровой страницы); есть найденный сайт — итог «сайт не найден» снимается,
     * нет — ставится (кроме «подключена»).
     *
     * @param crawl   обход
     * @param seq     блок
     * @param matches найденные сайты
     */
    @Transactional
    public void completeBlock(String crawl, int seq, List<SiteMatch> matches) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO company_site (company_id, host, evidence_url, source, crawl, proof)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (company_id, host) DO NOTHING
                """, matches, matches.size(), (statement, match) -> {
                statement.setLong(1, match.companyId());
                statement.setString(2, match.host());
                statement.setString(3, match.evidenceUrl());
                statement.setString(4, SOURCE);
                statement.setString(5, crawl);
                statement.setString(6, PROOF);
            });
        matches.stream().map(SiteMatch::companyId).distinct().sorted().forEach(this::reviewCommonCrawl);
        jdbcTemplate.update("UPDATE cc_index_block SET done = TRUE WHERE crawl = ? AND purpose = ? AND seq = ?", crawl,
                PURPOSE, seq);
    }

    /**
     * Правило Common Crawl для одной компании и следствия для её итога (см. {@link #completeBlock}).
     */
    private void reviewCommonCrawl(long companyId) {
        if (jdbcTemplate.update(REVIEW_COMMON_CRAWL, companyId) > 0) {
            jdbcTemplate.update("UPDATE company_site SET checked_at = NULL WHERE company_id = ? AND status = 'FOUND'",
                    companyId);
        }
        jdbcTemplate.update("""
                DELETE FROM company_check WHERE company_id = ? AND result = 'SITE_NOT_FOUND'
                  AND EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = ? AND s.status = 'FOUND')
                """, companyId, companyId);
        jdbcTemplate.update("""
                INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
                SELECT ?, 'SITE_NOT_FOUND', now(), now()
                WHERE NOT EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = ? AND s.status = 'FOUND')
                ON CONFLICT (company_id) DO UPDATE SET result = 'SITE_NOT_FOUND', checked_at = now()
                WHERE company_check.result <> 'CONNECTED'
                """, companyId, companyId);
    }

    /**
     * Скан обхода завершён: действующим компаниям без найденного сайта (кандидаты не считаются) и без итога —
     * итог «сайт не найден» (бизнес-описание §4.2). Повторный вызов ничего не меняет.
     *
     * @return сколько компаний получили итог
     */
    public int recordSitesNotFound() {
        return jdbcTemplate.update("""
                INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
                SELECT c.id, 'SITE_NOT_FOUND', now(), now() FROM company c
                WHERE c.terminated_on IS NULL AND NOT EXISTS (SELECT 1 FROM company_site s
                                                                WHERE s.company_id = c.id AND s.status = 'FOUND')
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
