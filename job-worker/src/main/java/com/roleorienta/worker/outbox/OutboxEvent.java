package com.roleorienta.worker.outbox;

/**
 * Неопубликованное событие outbox, захваченное публикатором.
 *
 * @param id        идентификатор события; становится {@code messageId} сообщения и ключом
 *                  идемпотентности у потребителя
 * @param eventType тип события
 * @param payload   тело события в JSON
 */
public record OutboxEvent(long id, String eventType, String payload) {
}
