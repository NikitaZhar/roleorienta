package com.roleorienta.worker.task;

/**
 * Задание, которое пора снова поставить в очередь.
 *
 * @param id           идентификатор задания
 * @param leaseExpired {@code true} — истекла аренда выполнения (исполнитель пропал); {@code false} — наступил срок
 *                     повтора ожидающего задания
 */
record DueTask(long id, boolean leaseExpired) {
}
