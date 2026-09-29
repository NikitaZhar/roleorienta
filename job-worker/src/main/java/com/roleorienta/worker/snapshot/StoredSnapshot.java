package com.roleorienta.worker.snapshot;

/**
 * Снимок, записанный в хранилище.
 *
 * @param objectKey ключ объекта
 * @param sha256    хеш тела (hex)
 */
public record StoredSnapshot(String objectKey, String sha256) {
}
