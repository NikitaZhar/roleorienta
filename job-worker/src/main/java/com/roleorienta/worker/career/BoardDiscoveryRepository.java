package com.roleorienta.worker.career;

import com.roleorienta.worker.site.IndexBlock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Обратный путь: блоки индекса с адресами досок, найденные доски, их проверка и подключение (JDBC).
 */
@Repository
public class BoardDiscoveryRepository {

    /** Назначение блоков первого прохода; по его обходу — последний известный обход. */
    static final String PURPOSE = "BOARD";
    private static final String COUNTRY = "SK";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public BoardDiscoveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param crawl обход
     * @param purpose назначение блоков: проход индекса
     * @return {@code true} — блоки обхода уже записаны
     */
    public boolean hasBlocks(String crawl, String purpose) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cc_index_block WHERE crawl = ? AND purpose = ?)", Boolean.class,
                crawl, purpose));
    }

    /**
     * @return последний обход, чьи блоки индекса записаны; пусто — ни одного
     */
    public Optional<String> lastCrawl() {
        return jdbcTemplate.queryForList("SELECT max(crawl) FROM cc_index_block WHERE purpose = ?", String.class,
                PURPOSE).stream().filter(Objects::nonNull).findFirst();
    }

    /**
     * @param crawl  обход
     * @param purpose назначение блоков: проход индекса
     * @param blocks блоки с адресами досок; уже записанные не меняются
     */
    @Transactional
    public void insertBlocks(String crawl, String purpose, List<IndexBlock> blocks) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO cc_index_block (crawl, purpose, seq, file, block_offset, block_length)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, blocks, blocks.size(), (statement, block) -> {
                statement.setString(1, crawl);
                statement.setString(2, purpose);
                statement.setInt(3, block.seq());
                statement.setString(4, block.file());
                statement.setLong(5, block.offset());
                statement.setInt(6, block.length());
            });
    }

    /**
     * @param crawl обход
     * @param purpose назначение блоков: проход индекса
     * @param limit сколько блоков
     * @return непросмотренные блоки по порядку
     */
    public List<IndexBlock> nextBlocks(String crawl, String purpose, int limit) {
        return jdbcTemplate.query("""
                SELECT seq, file, block_offset, block_length FROM cc_index_block
                WHERE crawl = ? AND purpose = ? AND NOT done ORDER BY seq LIMIT ?
                """, (row, number) -> new IndexBlock(row.getInt("seq"), row.getString("file"),
                row.getLong("block_offset"), row.getInt("block_length")), crawl, purpose, limit);
    }

    /**
     * Доски блока и отметка «просмотрен» — одной транзакцией; уже известная доска не дублируется.
     *
     * @param crawl  обход
     * @param purpose назначение блоков: проход индекса
     * @param seq    блок
     * @param boards доски из адресов блока
     */
    @Transactional
    public void completeBlock(String crawl, String purpose, int seq, Set<Board> boards) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO discovered_board (provider, board, crawl) VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, boards, boards.size(), (statement, board) -> {
                statement.setString(1, board.provider());
                statement.setString(2, board.board());
                statement.setString(3, crawl);
            });
        jdbcTemplate.update("UPDATE cc_index_block SET done = TRUE WHERE crawl = ? AND purpose = ? AND seq = ?",
                crawl, purpose, seq);
    }

    /**
     * @param limit        сколько досок
     * @param recheckAfter срок до перепроверки
     * @return доски, ещё не подключённые как источник, не проверенные или проверенные давнее срока;
     *         провайдеры чередуются (первая доска каждого, вторая каждого…) — проверка одного
     *         провайдера не задерживает другие
     */
    public List<Board> boardsToCheck(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT d.provider, d.board FROM discovered_board d
                WHERE (d.checked_at IS NULL OR d.checked_at < now() - make_interval(secs => ?))
                  AND NOT EXISTS (SELECT 1 FROM source s WHERE s.provider = d.provider AND s.board = d.board)
                ORDER BY d.checked_at NULLS FIRST,
                         row_number() OVER (PARTITION BY d.provider ORDER BY d.checked_at NULLS FIRST, d.board),
                         d.provider LIMIT ?
                """, (row, number) -> new Board(row.getString("provider"), row.getString("board")),
                (double) recheckAfter.toSeconds(), limit);
    }

    /**
     * Итог проверки доски; доска с вакансиями в Словакии подключается как источник (страна SK).
     * Связи с компанией нет: принадлежность доски юрлицу не подтверждена (решение владельца, §27).
     *
     * @param board  доска
     * @param slovak на доске есть вакансии в Словакии
     */
    @Transactional
    public void recordCheck(Board board, boolean slovak) {
        jdbcTemplate.update("UPDATE discovered_board SET checked_at = now(), slovak = ? WHERE provider = ? AND board = ?",
                slovak, board.provider(), board.board());
        if (slovak) {
            jdbcTemplate.update("""
                    INSERT INTO source (provider, board, country) VALUES (?, ?, ?)
                    ON CONFLICT (provider, board) DO NOTHING
                    """, board.provider(), board.board(), COUNTRY);
        }
    }
}
