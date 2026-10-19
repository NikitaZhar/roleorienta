package com.roleorienta.worker.snapshot;

import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Задание {@code SNAPSHOT_PURGE} (§130; технический документ §16.10): удалить из хранилища снимки, объекты которых не
 * перезаписывались дольше срока хранения ({@link SnapshotPurgeProperties#retention()}), — вместе с ними уходят и
 * сироты. Хранилище недоступно — повтор по общим правилам заданий.
 */
@Component
public class SnapshotPurgeHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "SNAPSHOT_PURGE";

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotPurgeHandler.class);

    private final SnapshotStore store;
    private final SnapshotPurgeProperties properties;
    private final Clock clock;

    /**
     * @param store      хранилище снимков
     * @param properties срок хранения
     * @param clock      часы — от них отсчитывается срок
     */
    public SnapshotPurgeHandler(SnapshotStore store, SnapshotPurgeProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        Instant cutoff = clock.instant().minus(properties.retention());
        try {
            int deleted = store.deleteOlderThan(cutoff);
            LOG.info("Snapshots written before {} deleted: {}", cutoff, deleted);
            return new TaskOutcome.Done();
        } catch (SdkException exception) {
            return new TaskOutcome.Retry("Snapshot storage: " + exception.getMessage(), Duration.ZERO);
        }
    }
}
