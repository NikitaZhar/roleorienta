package com.roleorienta.worker.career;

import com.roleorienta.worker.site.IndexBlock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Обратный путь: блоки индекса с адресами досок, найденные доски, их проверка и подключение (JDBC).
 */
@Repository
public class BoardDiscoveryRepository {

    private static final String PURPOSE = "BOARD";
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
     * @return {@code true} — блоки обхода уже записаны
     */
    public boolean hasBlocks(String crawl) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cc_index_block WHERE crawl = ? AND purpose = ?)", Boolean.class,
                crawl, PURPOSE));
    }

    /**
     * @param crawl  обход
     * @param blocks блоки с адресами досок; уже записанные не меняются
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
     * Доски блока и отметка «просмотрен» — одной транзакцией; уже известная доска не дублируется.
     *
     * @param crawl  обход
     * @param seq    блок
     * @param boards доски из адресов блока
     */
    @Transactional
    public void completeBlock(String crawl, int seq, Set<Board> boards) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO discovered_board (provider, board, crawl) VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, boards, boards.size(), (statement, board) -> {
                statement.setString(1, board.provider());
                statement.setString(2, board.board());
                statement.setString(3, crawl);
            });
        jdbcTemplate.update("UPDATE cc_index_block SET done = TRUE WHERE crawl = ? AND purpose = ? AND seq = ?",
                crawl, PURPOSE, seq);
    }

    /**
     * @param limit        сколько досок
     * @param recheckAfter срок до перепроверки
     * @return доски, ещё не подключённые как источник, не проверенные или проверенные давнее срока
     */
    public List<Board> boardsToCheck(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT d.provider, d.board FROM discovered_board d
                WHERE (d.checked_at IS NULL OR d.checked_at < now() - make_interval(secs => ?))
                  AND NOT EXISTS (SELECT 1 FROM source s WHERE s.provider = d.provider AND s.board = d.board)
                ORDER BY d.checked_at NULLS FIRST, d.provider, d.board LIMIT ?
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
