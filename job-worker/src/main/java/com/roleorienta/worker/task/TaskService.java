package com.roleorienta.worker.task;

import com.roleorienta.worker.outbox.OutboxRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Постановка заданий в очередь: задание и событие outbox записываются одной транзакцией, поэтому
 * задание не теряется и не отправляется без записи (технический документ §6).
 */
@Service
public class TaskService {

    /** Незавершённое задание цепочки, не менявшееся дольше, новую цепочку не держит (потерянное сообщение). */
    private static final Duration CHAIN_STALE_AFTER = Duration.ofDays(1);

    private final TaskRepository taskRepository;
    private final OutboxRepository outboxRepository;
    private final TaskProperties properties;

    /**
     * @param taskRepository   доступ к заданиям
     * @param outboxRepository доступ к outbox
     * @param properties       настройки заданий
     */
    public TaskService(TaskRepository taskRepository, OutboxRepository outboxRepository,
            TaskProperties properties) {
        this.taskRepository = taskRepository;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
    }

    /**
     * Ставит задание в очередь, если задания с этим ключом ещё нет.
     *
     * @param type    тип задания
     * @param taskKey ключ идемпотентности: повторная постановка с тем же ключом ничего не делает
     * @param payload параметры в JSON
     * @return {@code true} — задание создано; {@code false} — уже было
     */
    @Transactional
    public boolean enqueue(String type, String taskKey, String payload) {
        Optional<Long> taskId = taskRepository.insertIfAbsent(type, taskKey, payload);
        taskId.ifPresent(outboxRepository::insertTaskEvent);
        return taskId.isPresent();
    }

    /**
     * Ставит первое задание цепочки (шаг, который идёт заданиями одно за другим), только если цепочка этого типа
     * не идёт. Иначе ключ с днём запускал бы новую цепочку каждые сутки рядом с недошедшей вчерашней: цепочки шли
     * параллельно по одним и тем же компаниям, делили очередь к хосту, задания выходили за аренду и
     * перезапускались без конца (стенограмма §70).
     *
     * @param type    тип задания
     * @param taskKey ключ первого задания
     * @param payload параметры в JSON
     * @return {@code true} — задание создано; {@code false} — цепочка идёт или ключ уже был
     */
    @Transactional
    public boolean startChain(String type, String taskKey, String payload) {
        return !taskRepository.hasActive(type, CHAIN_STALE_AFTER) && enqueue(type, taskKey, payload);
    }

    /**
     * Снова ставит в очередь задания, у которых наступил срок повтора или истекла аренда.
     *
     * @return число перепоставленных заданий
     */
    @Transactional
    public int requeueDue() {
        List<Long> taskIds = taskRepository.claimDue(properties.requeueBatchSize());
        for (long taskId : taskIds) {
            taskRepository.markQueued(taskId);
            outboxRepository.insertTaskEvent(taskId);
        }
        return taskIds.size();
    }
}
