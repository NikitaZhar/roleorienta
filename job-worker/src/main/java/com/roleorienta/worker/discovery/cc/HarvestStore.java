package com.roleorienta.worker.discovery.cc;

import com.roleorienta.worker.adapters.workday.WorkdayBoard;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Состояние автоматического входа обнаружения в БД (§55, V26): курсор обхода индекса
 * ({@code harvest_cursor}) и накопитель найденных досок ({@code harvested_board}).
 *
 * <p>Прямой SQL через {@link JdbcTemplate}, как {@code SubscriptionLookup} (§39): таблицы
 * служебные, ведёт их только воркер, а нужны от них пара запросов с {@code ON CONFLICT},
 * {@code RETURNING} и {@code FOR UPDATE SKIP LOCKED}, которые в JPA выражаются хуже.</p>
 *
 * <p><b>Аренда вместо удержания транзакции.</b> Сетевая часть прохода (запросы к индексу)
 * идёт вне транзакции — иначе соединение из пула держалось бы всё время HTTP-запросов (та же
 * проблема, что C6). Единственность обхода обеспечивает аренда {@code lease_until}:
 * атомарный условный {@code UPDATE} выдаёт её одной реплике; истёкшая аренда (упавшая
 * реплика) перехватывается. Страница фиксируется {@link #recordPage} одной короткой
 * транзакцией: доски + продвижение курсора — атомарно, поэтому сбой между страницами
 * не теряет и не дублирует доски (повторная вставка гасится {@code ON CONFLICT}).</p>
 */
@Component
public class HarvestStore {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param jdbcTemplate       доступ к общей БД
     * @param transactionManager менеджер транзакций приложения (для атомарной фиксации страницы)
     */
    public HarvestStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Позиция обхода: докуда обойдена коллекция индекса.
     *
     * @param collection коллекция индекса ({@code null} — обход ещё не начинался)
     * @param pageSize   размер страницы, при котором посчитаны {@code pageCount}/{@code nextPage}
     *                   (номера страниц осмысленны только при том же размере)
     * @param pageCount  число страниц результата в этой коллекции
     * @param nextPage   следующая необработанная страница
     */
    public record Cursor(String collection, int pageSize, int pageCount, int nextPage) {
    }

    /**
     * Доска из накопителя, ждущая передачи в {@code DISCOVER_EMPLOYER}.
     *
     * @param id           идентификатор строки
     * @param providerCode код системы найма
     * @param slug         slug в написании, увиденном первым
     * @param baseUrl      базовый адрес ленты
     */
    public record PendingBoard(long id, String providerCode, String slug, String baseUrl) {
    }

    /**
     * Берёт аренду прохода входа, если она свободна или истекла. Строка курсора создаётся
     * при первом обращении.
     *
     * @param inputCode    код входа (напр. {@code cc-workday})
     * @param leaseSeconds длительность аренды, с
     * @return позиция курсора, если аренда получена; пусто — проход уже идёт в другой реплике
     */
    public Optional<Cursor> tryLease(String inputCode, long leaseSeconds) {
        jdbcTemplate.update(
                "INSERT INTO harvest_cursor (input_code) VALUES (?) ON CONFLICT (input_code) DO NOTHING",
                inputCode);
        List<Cursor> leased = jdbcTemplate.query(
                "UPDATE harvest_cursor SET lease_until = now() + make_interval(secs => ?), updated_at = now() "
                        + "WHERE input_code = ? AND (lease_until IS NULL OR lease_until < now()) "
                        + "RETURNING collection, page_size, page_count, next_page",
                (rs, i) -> new Cursor(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
                (double) leaseSeconds, inputCode);
        return leased.stream().findFirst();
    }

    /**
     * Фиксирует обработанную страницу одной транзакцией: новые доски (дубли по
     * {@code (provider_code, dedup_key)} пропускаются) и продвижение курсора.
     *
     * @param inputCode    код входа
     * @param providerCode код системы найма досок
     * @param position     позиция курсора после этой страницы
     * @param boards       доски страницы
     * @return сколько досок добавлено впервые
     */
    public int recordPage(String inputCode, String providerCode, Cursor position,
                          Collection<WorkdayBoard> boards) {
        Integer inserted = transactionTemplate.execute(status -> {
            int added = 0;
            for (WorkdayBoard board : boards) {
                added += jdbcTemplate.update(
                        "INSERT INTO harvested_board (input_code, provider_code, slug, dedup_key, base_url) "
                                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (provider_code, dedup_key) DO NOTHING",
                        inputCode, providerCode, board.slug(), board.dedupKey(), board.baseUrl());
            }
            updatePosition(inputCode, position);
            return added;
        });
        return inserted == null ? 0 : inserted;
    }

    /**
     * Запоминает позицию без досок (новая коллекция или смена размера страницы).
     *
     * @param inputCode код входа
     * @param position  новая позиция курсора
     */
    public void recordPosition(String inputCode, Cursor position) {
        updatePosition(inputCode, position);
    }

    private void updatePosition(String inputCode, Cursor position) {
        jdbcTemplate.update(
                "UPDATE harvest_cursor SET collection = ?, page_size = ?, page_count = ?, next_page = ?, "
                        + "updated_at = now() WHERE input_code = ?",
                position.collection(), position.pageSize(), position.pageCount(), position.nextPage(), inputCode);
    }

    /**
     * Освобождает аренду прохода (в конце прохода и при ошибке).
     *
     * @param inputCode код входа
     */
    public void releaseLease(String inputCode) {
        jdbcTemplate.update(
                "UPDATE harvest_cursor SET lease_until = NULL, updated_at = now() WHERE input_code = ?",
                inputCode);
    }

    /**
     * Берёт до {@code limit} досок в состоянии {@code NEW} по порядку обнаружения с
     * блокировкой строк ({@code FOR UPDATE SKIP LOCKED}). Вызывать внутри транзакции,
     * в которой затем {@link #mark} меняет их состояние.
     *
     * @param limit максимум досок
     * @return доски, ждущие передачи
     */
    public List<PendingBoard> lockNewBoards(int limit) {
        return jdbcTemplate.query(
                "SELECT id, provider_code, slug, base_url FROM harvested_board WHERE state = 'NEW' "
                        + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new PendingBoard(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                limit);
    }

    /**
     * Переводит доску в итоговое состояние fan-out.
     *
     * @param id    идентификатор строки
     * @param state {@code ENQUEUED} или {@code SKIPPED}
     */
    public void mark(long id, BoardState state) {
        jdbcTemplate.update(
                "UPDATE harvested_board SET state = ?, handled_at = now() WHERE id = ?", state.name(), id);
    }

    /** Итог передачи доски в контур обнаружения. */
    public enum BoardState {
        /** Задание {@code DISCOVER_EMPLOYER} поставлено через outbox. */
        ENQUEUED,
        /** Кандидат с такой доской уже есть — задание не нужно. */
        SKIPPED
    }
}
