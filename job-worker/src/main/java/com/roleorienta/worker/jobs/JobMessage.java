package com.roleorienta.worker.jobs;

/**
 * Разобранное фоновое задание, переданное обработчику.
 *
 * @param idempotencyKey ключ идемпотентности (messageId сообщения)
 * @param eventType      тип события из заголовка сообщения (например, тип задания)
 * @param payload        «сырое» тело события (JSON), как оно лежало в outbox
 */
public record JobMessage(String idempotencyKey, String eventType, String payload) {
}
