package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.UnlinkedSource;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Источники вакансий без компании для администратора (§206): список и закрытие (JDBC; источники читает job-worker).
 */
@Repository
public class AdminSourceRepository {

    /** Сколько источников показывать. */
    static final int SHOWN = 100;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public AdminSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return незакрытые источники без компании с открытыми публикациями: больше публикаций — первыми; не больше
     *         {@value #SHOWN}
     */
    public List<UnlinkedSource> unlinked() {
        return jdbcTemplate.query("""
                SELECT s.id, s.provider, s.board, count(*) AS open_postings,
                       (array_agg(p.title ORDER BY p.id))[1:5] AS titles
                FROM source s JOIN job_posting p ON p.source_id = s.id AND p.closed_at IS NULL
                WHERE s.closed_at IS NULL AND NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.source_id = s.id)
                GROUP BY s.id, s.provider, s.board
                ORDER BY open_postings DESC, s.id LIMIT ?
                """, (row, number) -> new UnlinkedSource(row.getLong("id"), row.getString("provider"),
                row.getString("board"), row.getLong("open_postings"), titles(row.getArray("titles"))), SHOWN);
    }

    /**
     * Закрывает источник без компании одной транзакцией (как уборка V71): источник больше не читается; его открытые
     * публикации закрываются; вакансия, у которой не осталось открытых публикаций, закрывается.
     *
     * @param sourceId источник
     * @return {@code false} — источника нет, он уже закрыт или связан с компанией
     */
    @Transactional
    public boolean close(long sourceId) {
        int closed = jdbcTemplate.update("""
                UPDATE source SET closed_at = now()
                WHERE id = ? AND closed_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.source_id = source.id)
                """, sourceId);
        if (closed == 0) {
            return false;
        }
        jdbcTemplate.update("""
                UPDATE vacancy v SET state = 'CLOSED', closed_at = now()
                WHERE v.state <> 'CLOSED'
                  AND EXISTS (SELECT 1 FROM job_posting p WHERE p.vacancy_id = v.id AND p.source_id = ?
                              AND p.closed_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM job_posting p WHERE p.vacancy_id = v.id AND p.source_id <> ?
                                  AND p.closed_at IS NULL)
                """, sourceId, sourceId);
        jdbcTemplate.update("UPDATE job_posting SET closed_at = now(), confirmed = FALSE "
                + "WHERE source_id = ? AND closed_at IS NULL", sourceId);
        return true;
    }

    private static List<String> titles(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
