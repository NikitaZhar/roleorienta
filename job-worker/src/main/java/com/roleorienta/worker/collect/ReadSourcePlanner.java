package com.roleorienta.worker.collect;

import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Ставит чтение каждого источника раз в сутки (технический документ §17); источник государственного
 * портала не ставится, если у компании есть своя кадровая страница (один канал на компанию,
 * бизнес-описание §4.1). Ключ задания
 * {@code read:<источник>:<дата>} — повторная постановка в тот же день ничего не добавляет. Источник, у которого
 * чтение за прошлый день ещё не выполнено (в очереди, выполняется, ждёт повтора), не ставится снова: иначе при
 * отставании чтения одного источника копились за несколько дней и шли параллельно (стенограмма §94, §95).
 */
@Service
public class ReadSourcePlanner {

    /** Начало ключа задания чтения; дальше — id источника и дата. */
    private static final String KEY_PREFIX = "read:";

    private final SourceRepository sources;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param sources     доступ к источникам
     * @param taskService постановка заданий
     * @param clock       часы
     */
    public ReadSourcePlanner(SourceRepository sources, TaskService taskService, Clock clock) {
        this.sources = sources;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Ставит чтение каждого источника на сегодня, если оно ещё не поставлено.
     */
    public void enqueueToday() {
        LocalDate today = LocalDate.now(clock);
        Set<String> pending = taskService.activeKeys(ReadSourceHandler.TYPE).stream()
                .map(ReadSourcePlanner::sourcePrefix).collect(Collectors.toSet());
        for (Source source : sources.findToRead(StatePortalAdapter.PROVIDER)) {
            String key = KEY_PREFIX + source.getId() + ":";
            if (!pending.contains(key)) {
                taskService.enqueue(ReadSourceHandler.TYPE, key + today, ReadSourceHandler.payload(source.getId()));
            }
        }
    }

    /**
     * Начало ключа {@code read:<источник>:} из ключа задания {@code read:<источник>:<дата>}.
     */
    private static String sourcePrefix(String taskKey) {
        return taskKey.substring(0, taskKey.lastIndexOf(':') + 1);
    }
}
