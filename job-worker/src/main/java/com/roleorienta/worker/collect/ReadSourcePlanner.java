package com.roleorienta.worker.collect;

import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Ставит чтения источников (технический документ §17; стенограмма §103). Источник читается, когда его последнее
 * чтение началось раньше {@link CollectProperties#readAfter()} назад (или его ещё не читали) — не реже раза в сутки
 * по времени, а не раз в календарную дату. Источник государственного портала не ставится, если у компании есть своя
 * кадровая страница (один канал на компанию, бизнес-описание §4.1). Источник с незавершённым чтением (в очереди,
 * выполняется, ждёт повтора) не ставится снова: чтения одного источника не копятся и не идут параллельно (§94, §95).
 * Ключ задания — {@code read:<источник>:<дата и час>}.
 *
 * <p>Провайдеры с общим хостом ({@link CollectProperties#sharedHostProviders()}: портал, Greenhouse …) читаются по
 * одному источнику за раз: следующий ставится, когда у провайдера нет чтения в очереди и в работе. Поэтому общий хост
 * с вежливостью 1 запрос/с держит не больше одного обработчика очереди сбора, остальные источники его не ждут.
 * Чтение, ждущее повтора, очередь провайдера не держит.</p>
 */
@Service
public class ReadSourcePlanner {

    /** Начало ключа задания чтения; дальше — id источника и час. */
    private static final String KEY_PREFIX = "read:";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final SourceRepository sources;
    private final TaskService taskService;
    private final CollectProperties properties;
    private final Clock clock;

    /**
     * @param sources     доступ к источникам
     * @param taskService постановка заданий
     * @param properties  срок до следующего чтения и провайдеры с общим хостом
     * @param clock       часы
     */
    public ReadSourcePlanner(SourceRepository sources, TaskService taskService, CollectProperties properties,
            Clock clock) {
        this.sources = sources;
        this.taskService = taskService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Ставит чтение каждого источника, которому пора читаться, кроме источников провайдеров с общим хостом (их ставит
     * {@link #continueSharedHosts()}). Вызывается тиком раз в час.
     */
    public void enqueueDue() {
        Set<String> pending = taskService.activeKeys(ReadSourceHandler.TYPE).stream()
                .map(ReadSourcePlanner::sourcePrefix).collect(Collectors.toSet());
        for (Source source : sources.findToRead(StatePortalAdapter.PROVIDER, readAfterSeconds())) {
            if (!properties.sharedHostProviders().contains(source.getProvider())
                    && !pending.contains(KEY_PREFIX + source.getId() + ":")) {
                enqueue(source);
            }
        }
    }

    /**
     * У каждого провайдера с общим хостом, у которого нет чтения в очереди и в работе, ставит чтение следующего
     * источника (сначала не читавшиеся и читавшиеся давнее). Вызывается частым тиком: промежуток между чтениями
     * общего хоста — не больше его интервала.
     */
    public void continueSharedHosts() {
        Set<String> reading = new HashSet<>(sources.providersReading());
        for (String provider : properties.sharedHostProviders()) {
            if (!reading.contains(provider)) {
                sources.findNextToRead(StatePortalAdapter.PROVIDER, readAfterSeconds(), provider)
                        .ifPresent(this::enqueue);
            }
        }
    }

    private void enqueue(Source source) {
        taskService.enqueue(ReadSourceHandler.TYPE,
                KEY_PREFIX + source.getId() + ":" + LocalDateTime.now(clock).format(HOUR),
                ReadSourceHandler.payload(source.getId()));
    }

    private double readAfterSeconds() {
        return properties.readAfter().toMillis() / MILLIS_PER_SECOND;
    }

    /**
     * Начало ключа {@code read:<источник>:} из ключа задания {@code read:<источник>:<час>}.
     */
    private static String sourcePrefix(String taskKey) {
        return taskKey.substring(0, taskKey.lastIndexOf(':') + 1);
    }
}
