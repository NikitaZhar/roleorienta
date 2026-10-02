package com.roleorienta.worker.outbox;

/**
 * Неопубликованное событие outbox, захваченное публикатором.
 *
 * @param id       идентификатор события; становится {@code messageId} сообщения
 * @param taskId   задание, которое событие ставит в очередь
 * @param taskType тип задания — по нему выбирается очередь (сбор или поиск)
 */
public record OutboxEvent(long id, long taskId, String taskType) {
}
