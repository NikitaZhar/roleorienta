package com.roleorienta.worker.collect;

import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskService;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * Ставит чтение каждого источника раз в сутки (технический документ §17). Ключ задания
 * {@code read:<источник>:<дата>} — повторная постановка в тот же день ничего не добавляет.
 */
@Service
public class ReadSourcePlanner {

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
        for (Source source : sources.findAll()) {
            taskService.enqueue(ReadSourceHandler.TYPE, "read:" + source.getId() + ":" + today,
                    ReadSourceHandler.payload(source.getId()));
        }
    }
}
