package com.roleorienta.worker.snapshot;

import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Клиент S3 (AWS SDK v2) для хранилища снимков. {@code endpointOverride} направляет запросы в
 * указанное хранилище (MinIO), {@code forcePathStyle} — адрес вида {@code <хост>/<корзина>/<ключ>},
 * который MinIO понимает без DNS-имён корзин. Таймауты ограничивают вызов и каждую попытку;
 * число повторов — по умолчанию SDK (ограничено).
 * https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html
 */
@Configuration(proxyBeanMethods = false)
public class SnapshotConfig {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * @param properties адрес и доступ к хранилищу
     * @return клиент; соединение устанавливается при первом запросе
     */
    @Bean(destroyMethod = "close")
    public S3Client snapshotS3Client(SnapshotProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .forcePathStyle(true)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(CALL_TIMEOUT)
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .build())
                .build();
    }
}
