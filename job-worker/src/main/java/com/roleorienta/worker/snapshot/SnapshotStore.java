package com.roleorienta.worker.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Запись снимков исходных ответов источников в S3-хранилище (технический документ §16.10).
 *
 * <p>Ключ — {@code <источник>/<sha256 тела>}: неизменяемый, одинаковый ответ хранится одним
 * объектом, повторная запись того же ключа безвредна. Корзина создаётся при первой записи.
 * Сбой хранилища не прерывает чтение источника: снимок пропускается с записью в журнал.</p>
 */
@Component
public class SnapshotStore {

    private static final String CONTENT_TYPE = "text/plain; charset=utf-8";

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotStore.class);

    private final S3Client s3;
    private final String bucket;
    private volatile boolean bucketReady;

    /**
     * @param s3         клиент хранилища
     * @param properties корзина снимков
     */
    public SnapshotStore(S3Client s3, SnapshotProperties properties) {
        this.s3 = s3;
        this.bucket = properties.bucket();
    }

    /**
     * Записывает тело ответа источника.
     *
     * @param sourceId источник
     * @param body     тело ответа
     * @return записанный снимок; пусто — хранилище недоступно (в журнале)
     */
    public Optional<StoredSnapshot> put(long sourceId, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(bytes);
        String key = sourceId + "/" + sha256;
        try {
            ensureBucket();
            s3.putObject(request -> request.bucket(bucket).key(key).contentType(CONTENT_TYPE),
                    RequestBody.fromBytes(bytes));
            return Optional.of(new StoredSnapshot(key, sha256));
        } catch (SdkException exception) {
            LOG.warn("Snapshot {} not stored: {}", key, exception.getMessage());
            return Optional.empty();
        }
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            s3.headBucket(request -> request.bucket(bucket));
        } catch (NoSuchBucketException missing) {
            try {
                s3.createBucket(request -> request.bucket(bucket));
            } catch (BucketAlreadyOwnedByYouException createdByAnotherReplica) {
                LOG.debug("Bucket {} created concurrently", bucket);
            }
        }
        bucketReady = true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
