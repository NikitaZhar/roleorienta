package com.roleorienta.worker.task;

/**
 * Захваченное задание вместе с номером захвата ({@code task.claim_seq}): продление аренды и запись итога проходят,
 * только пока номер в БД тот же — задание не перепоставлено и не захвачено другим исполнителем (стенограмма §95).
 *
 * @param task     задание для обработчика
 * @param claimSeq номер этого захвата
 */
record ClaimedTask(TaskRecord task, int claimSeq) {
}
