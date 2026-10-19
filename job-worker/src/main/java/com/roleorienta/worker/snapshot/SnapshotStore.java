package com.roleorienta.worker.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Запись и удаление по сроку снимков исходных ответов источников в S3-хранилище (технический документ §16.10).
 *
 * <p>Ключ — {@code <источник>/<sha256 тела>}: неизменяемый, одинаковый ответ хранится одним
 * объектом, повторная запись того же ключа безвредна. Корзина создаётся при первой записи.
 * Сбой хранилища не прерывает чтение источника: снимок пропускается с записью в журнал.</p>
 */
@Component
public class SnapshotStore {

    private static final String CONTENT_TYPE = "text/plain; charset=utf-8";

    /** Больше ключей в одном запросе удаления S3 не принимает. */
    private static final int MAX_DELETE_BATCH = 1000;

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

    /**
     * Удаляет объекты корзины, не перезаписанные с границы (§130): срок хранения снимков и сироты — объекты без
     * ссылки в БД — одним правилом. Ключ общий для одинаковых ответов источника, поэтому ответ, который источник
     * отдаёт снова, перезаписывается (новое {@code LastModified}) и остаётся. Строки {@code source_snapshot} в БД не
     * трогаются — метаданные удалённого снимка сохраняются. Объекты перечисляются страницами (пагинатор SDK сам
     * запрашивает следующие), удаляются пачками до {@value #MAX_DELETE_BATCH} ключей одним запросом.
     *
     * @param cutoff граница: объекты, записанные раньше неё, удаляются
     * @return число удалённых объектов; корзины ещё нет — 0
     * @throws SdkException хранилище не ответило или отказало (задание повторится)
     */
    public int deleteOlderThan(Instant cutoff) {
        List<ObjectIdentifier> batch = new ArrayList<>();
        int deleted = 0;
        try {
            for (S3Object object : s3.listObjectsV2Paginator(request -> request.bucket(bucket)).contents()) {
                if (object.lastModified().isBefore(cutoff)) {
                    batch.add(ObjectIdentifier.builder().key(object.key()).build());
                }
                if (batch.size() == MAX_DELETE_BATCH) {
                    deleted += delete(batch);
                    batch.clear();
                }
            }
        } catch (NoSuchBucketException missing) {
            return deleted;
        }
        return deleted + delete(batch);
    }

    /**
     * Удаляет пачку ключей одним запросом; в ответе — только ключи, удалить которые не удалось ({@code quiet}).
     *
     * @return сколько удалено
     */
    private int delete(List<ObjectIdentifier> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        List<ObjectIdentifier> objects = List.copyOf(keys);
        DeleteObjectsResponse response = s3.deleteObjects(request -> request.bucket(bucket)
                .delete(deletion -> deletion.objects(objects).quiet(true)));
        int failed = response.errors().size();
        if (failed > 0) {
            LOG.warn("{} snapshots not deleted, first: {}", failed, response.errors().get(0));
        }
        return objects.size() - failed;
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
