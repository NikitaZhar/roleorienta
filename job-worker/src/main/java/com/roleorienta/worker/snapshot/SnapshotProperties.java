package com.roleorienta.worker.snapshot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки S3-хранилища снимков ({@code app.snapshot.*}); локально — MinIO из docker-compose.
 *
 * @param endpoint  адрес хранилища, например {@code http://localhost:9000}
 * @param region    регион подписи запросов; MinIO принимает любой
 * @param bucket    корзина снимков; создаётся при первой записи
 * @param accessKey ключ доступа (из окружения)
 * @param secretKey секрет (из окружения; в журнал не пишется)
 */
@ConfigurationProperties("app.snapshot")
public record SnapshotProperties(String endpoint, String region, String bucket, String accessKey,
        String secretKey) {

    /**
     * Секрет не попадает в журнал при выводе настроек.
     *
     * @return настройки без ключей
     */
    @Override
    public String toString() {
        return "SnapshotProperties[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + "]";
    }
}
