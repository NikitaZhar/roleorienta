package com.roleorienta.worker.intake;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Реестр юрлиц Словакии — выгрузки RPO (технический документ §5.1, подэтап 1.6; решение владельца,
 * §24). Партия общего сбора для страны SK ({@link RegistryIntakeHandler}).
 *
 * <ol>
 *   <li>Курсора нет — он ставится на начало последней полной выгрузки.</li>
 *   <li>Полная выгрузка читается файл за файлом. Файл — {@code {"exportDate": …, "results": [ … ]}}
 *       (проверено на живых выгрузках, §24); парсер доходит до массива {@code results} и читает
 *       записи по одной, не загружая файл в память; порция записей
 *       ({@link IntakeProperties#batchSize()}) пишется вместе со сдвигом курсора одной
 *       транзакцией. После остановки чтение продолжается с места курсора: обработанные записи
 *       файла пропускаются.</li>
 *   <li>Полная выгрузка прочитана — дальше ежедневные выгрузки начиная с её даты (повтор записей
 *       безвреден: запись по регистрационному номеру). Выгрузка за сегодня ещё не полна и не
 *       читается.</li>
 *   <li>Ежедневной выгрузки нет, а её дата уже вне срока хранения — цепочка прервана: курсор
 *       переходит на более новую полную выгрузку; её нет — ожидание.</li>
 * </ol>
 *
 * <p>Партия — не больше {@link IntakeProperties#recordsPerTask()} записей. Место обработки —
 * курсор {@code intake_cursor}; курсор, сдвинутый другим заданием, — {@link OptimisticLockingFailureException}.</p>
 */
@Component
public class RpoRegistry implements CountryRegistry {

    /** Страна реестра. */
    static final String COUNTRY = "SK";

    private static final String REGISTRY = "RPO";
    private static final String RECORDS_FIELD = "results";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader RECORDS = JSON.readerFor(JsonNode.class);
    private static final Logger LOG = LoggerFactory.getLogger(RpoRegistry.class);

    private final RpoExports exports;
    private final IntakeRepository repository;
    private final IntakeProperties properties;
    private final Clock clock;

    /**
     * @param exports    выгрузки RPO
     * @param repository компании и курсор
     * @param properties размеры порций и срок хранения выгрузок
     * @param clock      часы
     */
    public RpoRegistry(RpoExports exports, IntakeRepository repository, IntakeProperties properties, Clock clock) {
        this.exports = exports;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String country() {
        return COUNTRY;
    }

    /**
     * Партия с места курсора: курсора нет — он ставится на начало последней полной выгрузки.
     *
     * @return {@code true} — записи ещё есть; {@code false} — читать пока нечего (ждать ежедневной)
     * @throws IOException полной выгрузки нет или хранилище не ответило
     */
    @Override
    public boolean intakeBatch() throws IOException {
        Optional<IntakeCursor> found = repository.findCursor(COUNTRY);
        if (found.isEmpty()) {
            Optional<LocalDate> latest = exports.latestInit();
            if (latest.isEmpty()) {
                throw new IOException("No full RPO export");
            }
            found = Optional.of(repository.createCursor(COUNTRY, REGISTRY, latest.get()));
        }
        IntakeCursor cursor = found.get();
        long budget = properties.recordsPerTask();
        while (budget > 0) {
            String key = cursor.dailyDate() == null ? initFile(cursor) : dailyFile(cursor);
            if (key == null) {
                Optional<IntakeCursor> moved = nextPlace(cursor);
                if (moved.isEmpty()) {
                    return false;
                }
                cursor = moved.get();
                continue;
            }
            Progress progress = read(key, cursor, budget);
            budget -= progress.records();
            cursor = progress.finished() ? repository.moveTo(COUNTRY, progress.cursor(), afterFile(progress.cursor()))
                    : progress.cursor();
        }
        return true;
    }

    /**
     * @return ключ читаемого файла полной выгрузки; {@code null} — файлы кончились или выгрузки нет
     */
    private String initFile(IntakeCursor cursor) {
        List<String> files = exports.initFiles(cursor.exportDate());
        return cursor.fileIndex() < files.size() ? files.get(cursor.fileIndex()) : null;
    }

    /**
     * @return ключ следующей ежедневной выгрузки; {@code null} — её нет или она за сегодня
     */
    private String dailyFile(IntakeCursor cursor) {
        LocalDate next = cursor.dailyDate().plusDays(1);
        String key = RpoExports.dailyKey(next);
        return next.isBefore(LocalDate.now(clock)) && exports.exists(key) ? key : null;
    }

    /**
     * Читать нечего. Полная выгрузка прочитана до конца — переход к ежедневным; её файлов уже нет,
     * или ежедневная вне срока хранения — переход на более новую полную выгрузку; иначе — ждать.
     */
    private Optional<IntakeCursor> nextPlace(IntakeCursor cursor) {
        boolean initRead = cursor.dailyDate() == null && !exports.initFiles(cursor.exportDate()).isEmpty();
        if (initRead) {
            LOG.info("RPO full export {} is read", cursor.exportDate());
            return Optional.of(repository.moveTo(COUNTRY, cursor,
                    new IntakeCursor(cursor.exportDate(), 0, 0, cursor.exportDate().minusDays(1), 0)));
        }
        LocalDate oldest = LocalDate.now(clock).minusDays(properties.retention().toDays());
        boolean chainBroken = cursor.dailyDate() == null || cursor.dailyDate().plusDays(1).isBefore(oldest);
        Optional<LocalDate> latest = chainBroken ? exports.latestInit() : Optional.empty();
        if (latest.isPresent() && latest.get().isAfter(cursor.exportDate())) {
            LOG.warn("RPO export chain broken at {}, restart from full export {}", cursor, latest.get());
            return Optional.of(repository.moveTo(COUNTRY, cursor, new IntakeCursor(latest.get(), 0, 0, null, 0)));
        }
        return Optional.empty();
    }

    private static IntakeCursor afterFile(IntakeCursor cursor) {
        return cursor.dailyDate() == null
                ? new IntakeCursor(cursor.exportDate(), cursor.fileIndex() + 1, 0, null, 0)
                : new IntakeCursor(cursor.exportDate(), 0, 0, cursor.dailyDate().plusDays(1), 0);
    }

    /**
     * Читает файл с места курсора: не больше {@code budget} записей, порциями по
     * {@link IntakeProperties#batchSize()}.
     */
    private Progress read(String key, IntakeCursor start, long budget) throws IOException {
        IntakeCursor cursor = start;
        long index = 0;
        long records = 0;
        List<RegistryCompany> batch = new ArrayList<>();
        try (InputStream input = exports.open(key); JsonParser parser = JSON.createParser(input)) {
            moveToRecords(parser, key);
            while (records < budget && parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode value = RECORDS.readValue(parser);
                index++;
                if (index <= cursor.recordOffset()) {
                    continue;
                }
                records++;
                RpoRecordParser.parse(value).ifPresent(batch::add);
                if (records % properties.batchSize() == 0) {
                    cursor = repository.applyBatch(COUNTRY, REGISTRY, cursor, batch, index);
                    batch.clear();
                }
            }
            boolean finished = records < budget || parser.nextToken() != JsonToken.START_OBJECT;
            if (index > cursor.recordOffset()) {
                cursor = repository.applyBatch(COUNTRY, REGISTRY, cursor, batch, index);
            }
            LOG.info("RPO {}: {} records, finished={}", key, records, finished);
            return new Progress(cursor, records, finished);
        }
    }

    /**
     * Ставит парсер на начало массива записей: массив {@code results} корневого объекта или сам
     * корневой массив.
     *
     * @throws IOException массива записей нет
     */
    private static void moveToRecords(JsonParser parser, String key) throws IOException {
        JsonToken first = parser.nextToken();
        if (first == JsonToken.START_ARRAY) {
            return;
        }
        if (first == JsonToken.START_OBJECT) {
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                if (parser.nextToken() == JsonToken.START_ARRAY && RECORDS_FIELD.equals(field)) {
                    return;
                }
                parser.skipChildren();
            }
        }
        throw new IOException("No records array in RPO export " + key);
    }

    /**
     * @param cursor   курсор после чтения
     * @param records  обработано записей
     * @param finished файл дочитан
     */
    private record Progress(IntakeCursor cursor, long records, boolean finished) {
    }
}
