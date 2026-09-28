package com.roleorienta.worker.outbox;

/**
 * Неопубликованное событие outbox, захваченное публикатором.
 *
 * @param id        идентификатор события; становится {@code messageId} сообщения
 * @param eventType тип события
 * @param payload   тело события в JSON
 * @param taskId    задание, которое событие ставит в очередь; {@code null} — событие не о задании
 */
public record OutboxEvent(long id, String eventType, String payload, Long taskId) {
}
