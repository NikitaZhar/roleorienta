package com.roleorienta.worker.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к таблице {@code processed_message} (идемпотентность потребителя).
 */
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {

    /**
     * Пытается зафиксировать ключ как обработанный. {@code ON CONFLICT DO NOTHING}
     * делает операцию атомарной и безопасной при гонке двух доставок: первая
     * вставка вернёт 1 (обрабатываем), повторная — 0 (уже обработано, пропускаем).
     * Документация:
     * https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT
     *
     * @param key ключ идемпотентности (messageId сообщения)
     * @return 1, если ключ зафиксирован впервые; 0, если он уже существовал
     */
    @Modifying
    @Query(value = "INSERT INTO processed_message (idempotency_key) VALUES (:key) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int markProcessed(@Param("key") String key);
}
