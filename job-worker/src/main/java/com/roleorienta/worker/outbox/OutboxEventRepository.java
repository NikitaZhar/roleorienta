package com.roleorienta.worker.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к таблице {@code outbox_event} для публикатора.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Забирает пачку ещё не опубликованных событий, блокируя их строки, и
     * пропускает строки, уже захваченные другой репликой
     * ({@code FOR UPDATE SKIP LOCKED}). Благодаря этому каждое событие
     * отправляет ровно один публикатор даже при нескольких репликах worker
     * (ADR-12). Документация:
     * https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
     *
     * <p>Запрос обязан выполняться внутри транзакции публикатора: блокировки
     * строк удерживаются до её завершения.</p>
     *
     * @param batch максимальный размер пачки
     * @return список захваченных неопубликованных событий (в порядке возрастания id)
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            LIMIT :batch
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("batch") int batch);

    /**
     * Помечает событие опубликованным. Вызывается только для сообщений с
     * подтверждённой маршрутизацией (A15).
     *
     * @param id          идентификатор события
     * @param publishedAt момент публикации (UTC)
     */
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.publishedAt = :publishedAt WHERE e.id = :id")
    void markPublished(@Param("id") Long id, @Param("publishedAt") Instant publishedAt);

    /**
     * Увеличивает счётчик попыток публикации на единицу (для событий, которые
     * оказались немаршрутизируемыми и остаются неопубликованными).
     *
     * @param id идентификатор события
     */
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.attempts = e.attempts + 1 WHERE e.id = :id")
    void incrementAttempts(@Param("id") Long id);
}
