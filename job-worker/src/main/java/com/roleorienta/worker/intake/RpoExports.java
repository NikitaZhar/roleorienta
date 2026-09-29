package com.roleorienta.worker.intake;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Выгрузки RPO в открытом S3-совместимом хранилище МВД Словакии (лицензия CC BY 4.0):
 * {@code batch-init/init_<дата>_<NNN>.json.gz} — полная выгрузка, раз в месяц, несколькими файлами;
 * {@code batch-daily/actual_<дата>.json.gz} — новые и изменённые за сутки записи. Файлы хранятся
 * 45 дней. https://rpo.minv.sk/rpo-api-doc.html
 *
 * <p>Доступ без ключей ({@code AnonymousCredentialsProvider}), адрес вида
 * {@code <хост>/<корзина>/<ключ>} ({@code forcePathStyle}). Это не страница сайта, а официальный
 * набор открытых данных, поэтому читается собственным клиентом, а не {@code ExternalHttpClient}
 * (у него потолок тела 5 МБ, а файлы — до ~100 МБ). Файл читается потоком; ожидание ограничено
 * таймаутами HTTP-клиента SDK на соединение и на чтение.</p>
 */
@Component
public class RpoExports implements AutoCloseable {

    private static final String INIT_PREFIX = "batch-init/init_";
    private static final Pattern INIT_FILE = Pattern.compile(
            "batch-init/init_(\\d{4}-\\d{2}-\\d{2})_\\d{3}\\.json\\.gz");
    private static final int NOT_FOUND = 404;
    /** Регион нужен SDK для построения запроса; при доступе без ключей не проверяется. */
    private static final Region REGION = Region.of("eu-frankfurt-1");

    private final S3Client s3;
    private final String bucket;

    /**
     * @param properties адрес хранилища и корзина
     */
    public RpoExports(IntakeProperties properties) {
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(properties.rpoEndpoint()))
                .region(REGION)
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .forcePathStyle(true)
                .build();
        this.bucket = properties.rpoBucket();
    }

    /**
     * @param date дата ежедневной выгрузки
     * @return ключ её файла
     */
    public static String dailyKey(LocalDate date) {
        return "batch-daily/actual_" + date + ".json.gz";
    }

    /**
     * @return дата последней полной выгрузки; пусто — выгрузок нет
     */
    public Optional<LocalDate> latestInit() {
        return initKeys(INIT_PREFIX).stream().map(RpoExports::initDate).max(LocalDate::compareTo);
    }

    /**
     * @param date дата полной выгрузки
     * @return ключи её файлов по порядку; пусто — выгрузки уже нет
     */
    public List<String> initFiles(LocalDate date) {
        return initKeys(INIT_PREFIX + date + "_").stream().sorted().toList();
    }

    /**
     * @param key ключ файла
     * @return {@code true} — файл есть
     */
    public boolean exists(String key) {
        try {
            s3.headObject(request -> request.bucket(bucket).key(key));
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == NOT_FOUND) {
                return false;
            }
            throw exception;
        }
    }

    /**
     * @param key ключ файла
     * @return распакованное содержимое файла; закрывает вызывающий
     * @throws IOException файл не gzip или обрыв
     */
    public InputStream open(String key) throws IOException {
        return new GZIPInputStream(s3.getObject(request -> request.bucket(bucket).key(key)));
    }

    @Override
    public void close() {
        s3.close();
    }

    private List<String> initKeys(String prefix) {
        return s3.listObjectsV2Paginator(request -> request.bucket(bucket).prefix(prefix)).contents().stream()
                .map(S3Object::key).filter(key -> INIT_FILE.matcher(key).matches()).toList();
    }

    private static LocalDate initDate(String key) {
        Matcher matcher = INIT_FILE.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not an init export file: " + key);
        }
        return LocalDate.parse(matcher.group(1));
    }
}
