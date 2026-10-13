package com.roleorienta.worker.task;

import com.roleorienta.worker.outbox.OutboxRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
        return startChain(type, "", taskKey, payload);
    }

    /**
     * Как {@link #startChain(String, String, String)}, но цепочка — задания типа с ключом, начинающимся на
     * {@code chainPrefix}: у типа несколько независимых цепочек (шаги портала «список», «проверка», «сайт»).
     *
     * @param type        тип задания
     * @param chainPrefix начало ключей заданий цепочки
     * @param taskKey     ключ первого задания (начинается с {@code chainPrefix})
     * @param payload     параметры в JSON
     * @return {@code true} — задание создано; {@code false} — цепочка идёт или ключ уже был
     */
    @Transactional
    public boolean startChain(String type, String chainPrefix, String taskKey, String payload) {
        return !taskRepository.hasActive(type, chainPrefix, CHAIN_STALE_AFTER) && enqueue(type, taskKey, payload);
    }

    /**
     * Ключи незавершённых заданий типа — чтобы не ставить второе задание на ту же работу (чтение источника).
     *
     * @param type тип задания
     * @return ключи заданий в QUEUED, RUNNING, WAITING
     */
    public List<String> activeKeys(String type) {
        return taskRepository.activeKeys(type);
    }

    /**
     * Захват задания исполнителем. Тип из группы заданий одного внешнего хоста ({@code app.task.groups.exclusive})
     * захватывается, только если ни одно задание группы не выполняется; иначе задание откладывается без попытки на
     * {@code app.task.groups.busy-delay} и потом снова ставится перепостановкой (стенограмма §95). Блокировка группы
     * держится до конца транзакции — два исполнителя не захватят задания группы одновременно.
     *
     * @param taskId идентификатор задания
     * @return захваченное задание; пусто — задание не в очереди (уже выполнено, повторная доставка) или отложено
     */
    @Transactional
    public Optional<ClaimedTask> claim(long taskId) {
        Optional<Map.Entry<String, List<String>>> group = taskRepository.typeOf(taskId).flatMap(this::groupOf);
        if (group.isEmpty()) {
            return taskRepository.claim(taskId, properties.lease());
        }
        taskRepository.lockGroup(group.get().getKey());
        Optional<ClaimedTask> claimed = taskRepository.claimIfGroupFree(taskId, properties.lease(),
                group.get().getValue());
        if (claimed.isEmpty()) {
            taskRepository.deferQueued(taskId, properties.groups().busyDelay());
        }
        return claimed;
    }

    private Optional<Map.Entry<String, List<String>>> groupOf(String type) {
        return properties.groups().exclusive().entrySet().stream()
                .filter(entry -> entry.getValue().contains(type)).findFirst();
    }

    /**
     * Снова ставит в очередь задания, у которых наступил срок повтора или истекла аренда. Истекшая аренда — неудачная
     * попытка: на последней допустимой задание завершается неудачей (FAILED) и в очередь не ставится.
     *
     * @return число перепоставленных заданий
     */
    @Transactional
    public int requeueDue() {
        int requeued = 0;
        for (DueTask due : taskRepository.claimDue(properties.requeueBatchSize())) {
            boolean queued = true;
            if (due.leaseExpired()) {
                queued = taskRepository.expireLease(due.id(), properties.maxAttempts());
            } else {
                taskRepository.markQueued(due.id());
            }
            if (queued) {
                outboxRepository.insertTaskEvent(due.id());
                requeued++;
            }
        }
        return requeued;
    }
}
