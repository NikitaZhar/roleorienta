package com.roleorienta.worker.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roleorienta.worker.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Удаление снимков по сроку (§130) на настоящем S3-хранилище (SeaweedFS из Testcontainers): граница подаётся
 * напрямую, чтобы не ждать срока.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SnapshotStoreTests {

    /** Больше одной страницы списка и одной пачки удаления S3 (по 1000). */
    private static final int MANY_OBJECTS = 1001;

    /** Время записи объекта S3 — с точностью до секунды: пауза перед перезаписью. */
    private static final long REWRITE_PAUSE_MS = 1_100;

    /** Запас на расхождение часов JVM и контейнера. */
    private static final Duration CLOCK_MARGIN = Duration.ofMinutes(1);

    @Autowired
    private SnapshotStore store;

    @Autowired
    private S3Client s3;

    @Autowired
    private SnapshotProperties properties;

    /**
     * Объект, записанный только что: граница за 30 дней до сейчас — остаётся; граница после записи — удалён.
     */
    @Test
    void deletesOnlyObjectsWrittenBeforeCutoff() {
        String key = store.put(990_001L, "{\"jobs\":[]}").orElseThrow().objectKey();

        store.deleteOlderThan(Instant.now().minus(Duration.ofDays(30)));
        assertThat(s3.headObject(request -> request.bucket(properties.bucket()).key(key)).lastModified()).isNotNull();

        int deleted = store.deleteOlderThan(Instant.now().plus(CLOCK_MARGIN));

        assertThat(deleted).isPositive();
        assertThatThrownBy(() -> s3.headObject(request -> request.bucket(properties.bucket()).key(key)))
                .isInstanceOf(NoSuchKeyException.class);
    }

    /**
     * Повторная запись того же ответа (тот же ключ) обновляет время записи: граница между первой и второй записью
     * объект не удаляет (§130 — повторяющийся ответ источника остаётся).
     *
     * @throws InterruptedException пауза прервана
     */
    @Test
    void rewrittenObjectIsKept() throws InterruptedException {
        String body = "{\"jobs\":[\"rewritten\"]}";
        String key = store.put(990_002L, body).orElseThrow().objectKey();
        Instant firstWrite = lastModified(key);
        Thread.sleep(REWRITE_PAUSE_MS);
        store.put(990_002L, body);

        store.deleteOlderThan(firstWrite.plusMillis(1));

        assertThat(lastModified(key)).isAfter(firstWrite);
    }

    /**
     * {@value #MANY_OBJECTS} объектов — больше одной страницы списка и одной пачки удаления: удаляются все.
     */
    @Test
    void deletesMoreObjectsThanOnePageAndBatch() {
        for (int index = 0; index < MANY_OBJECTS; index++) {
            store.put(990_003L, "{\"jobs\":[" + index + "]}");
        }

        int deleted = store.deleteOlderThan(Instant.now().plus(CLOCK_MARGIN));

        assertThat(deleted).isGreaterThanOrEqualTo(MANY_OBJECTS);
        assertThat(s3.listObjectsV2(request -> request.bucket(properties.bucket()).prefix("990003/")).keyCount())
                .isZero();
    }

    private Instant lastModified(String key) {
        return s3.headObject(request -> request.bucket(properties.bucket()).key(key)).lastModified();
    }
}
