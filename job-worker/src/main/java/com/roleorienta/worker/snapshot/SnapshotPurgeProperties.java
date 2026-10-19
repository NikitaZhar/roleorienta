package com.roleorienta.worker.snapshot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки удаления снимков по сроку ({@code app.snapshot.purge.*}; §130). Включение — {@code enabled} (читает
 * {@link SnapshotPurgeTick}), период тика — {@code interval-ms}.
 *
 * @param retention сколько хранится снимок после последней записи его объекта (решение владельца 2026-10-19 — 30 дней)
 */
@ConfigurationProperties("app.snapshot.purge")
public record SnapshotPurgeProperties(@DefaultValue("30d") Duration retention) {
}
