package com.roleorienta.worker.task;

/**
 * Задание, захваченное на выполнение.
 *
 * @param id       идентификатор задания
 * @param type     тип задания; по нему выбирается обработчик
 * @param payload  параметры задания в JSON
 * @param attempts число уже состоявшихся неудачных попыток
 */
public record TaskRecord(long id, String type, String payload, int attempts) {
}
