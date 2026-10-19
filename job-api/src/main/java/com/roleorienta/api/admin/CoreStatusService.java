package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.CoreStatus;
import com.roleorienta.api.admin.AdminDtos.ReadStats;
import com.roleorienta.api.admin.AdminDtos.TaskStats;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Состояние ядра сейчас для администратора (§142; Порядок 1.10, п. 6 — минимум наблюдаемости): чтения и задания и
 * тревоги «ядро остановилось». Чтение источника — не реже раза в сутки, но читаются они круглые сутки: 2 ч без
 * успешного чтения — остановка; задание дольше 2 ч в очереди — очередь не разбирается.
 */
@Service
public class CoreStatusService {

    /** Нет успешного чтения дольше — тревога. */
    static final Duration NO_READ_AFTER = Duration.ofHours(2);

    /** Задание в очереди дольше — тревога. */
    static final Duration QUEUE_STALLED_AFTER = Duration.ofHours(2);

    /** Тревога: успешного чтения нет дольше {@link #NO_READ_AFTER}. */
    static final String NO_RECENT_READ = "NO_RECENT_READ";

    /** Тревога: задание в очереди дольше {@link #QUEUE_STALLED_AFTER}. */
    static final String QUEUE_STALLED = "QUEUE_STALLED";

    private final CoreStatusRepository repository;

    /**
     * @param repository чтения и задания в БД
     */
    public CoreStatusService(CoreStatusRepository repository) {
        this.repository = repository;
    }

    /**
     * @return состояние ядра сейчас
     */
    public CoreStatus status() {
        Instant now = repository.now();
        ReadStats reads = repository.reads();
        TaskStats tasks = repository.tasks();
        List<String> warnings = new ArrayList<>();
        if (reads.lastSuccessAt() == null || reads.lastSuccessAt().isBefore(now.minus(NO_READ_AFTER))) {
            warnings.add(NO_RECENT_READ);
        }
        if (tasks.oldestQueuedAt() != null && tasks.oldestQueuedAt().isBefore(now.minus(QUEUE_STALLED_AFTER))) {
            warnings.add(QUEUE_STALLED);
        }
        return new CoreStatus(now, reads, tasks, List.copyOf(warnings));
    }
}
