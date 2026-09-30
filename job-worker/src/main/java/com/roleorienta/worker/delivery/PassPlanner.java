package com.roleorienta.worker.delivery;

import com.roleorienta.worker.task.TaskService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Проход по расписанию (бизнес-описание §4.5; технический документ §7): проход окна и задание
 * {@code RUN_PASS} на каждую активную версию условий — одной транзакцией. Ключ задания — «проход +
 * версия условий»: повторное планирование окна заданий не добавляет.
 */
@Service
public class PassPlanner {

    private final DeliveryRepository repository;
    private final TaskService taskService;

    /**
     * @param repository  проходы и условия
     * @param taskService постановка заданий
     */
    public PassPlanner(DeliveryRepository repository, TaskService taskService) {
        this.repository = repository;
        this.taskService = taskService;
    }

    /**
     * @param windowStart начало окна расписания
     * @return id прохода
     */
    @Transactional
    public long plan(Instant windowStart) {
        long passRunId = repository.passRun(windowStart);
        for (long conditionId : repository.activeConditionIds()) {
            taskService.enqueue(RunPassHandler.TYPE, RunPassHandler.taskKey(passRunId, conditionId),
                    RunPassHandler.payload(passRunId, conditionId));
        }
        return passRunId;
    }
}
