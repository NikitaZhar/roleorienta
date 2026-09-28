package com.roleorienta.worker.outbox;

/**
 * Неопубликованное событие outbox, захваченное публикатором.
 *
 * @param id     идентификатор события; становится {@code messageId} сообщения
 * @param taskId задание, которое событие ставит в очередь
 */
public record OutboxEvent(long id, long taskId) {
}
