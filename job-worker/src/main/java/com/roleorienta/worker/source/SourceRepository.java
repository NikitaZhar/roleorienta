package com.roleorienta.worker.source;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Доступ к источникам. {@link JpaRepository} даёт готовые операции (поиск по id, сохранение,
 * список); реализацию создаёт Spring Data. https://docs.spring.io/spring-data/jpa/reference/
 */
public interface SourceRepository extends JpaRepository<Source, Long> {

    /**
     * Незакрытые источники (закрывает администратор, §206) — общая часть запросов. Читаются все каналы компании
     * (решение владельца 2026-11-03, бизнес-описание §4.1, стенограмма §260; заменяет «один канал на компанию»):
     * кадровые страницы, системы найма, государственный портал и площадка; одна вакансия разных каналов — одна запись
     * ({@code PostingRecorder}).
     */
    String OPEN = "SELECT s.* FROM source s WHERE s.closed_at IS NULL";

    /**
     * Источники к чтению (общая часть запросов {@link #findToRead} и {@link #findNextToRead}): {@link #OPEN} и
     * только те, чьё последнее чтение (обход {@code crawl_run}) началось раньше, чем {@code ?1} секунд назад, или
     * которых ещё не читали — чтение не реже раза в сутки по времени, а не по дате (§103).
     */
    String TO_READ = OPEN + """
              AND NOT EXISTS (SELECT 1 FROM crawl_run r
                              WHERE r.source_id = s.id AND r.started_at > now() - make_interval(secs => ?1))
            """;

    /** Порядок: сначала не читавшиеся, затем читавшиеся давнее. */
    String BY_LAST_READ = """
            ORDER BY (SELECT max(r.started_at) FROM crawl_run r WHERE r.source_id = s.id) NULLS FIRST, s.id
            """;

    /**
     * Источники, которым пора читаться ({@link #TO_READ}). {@link Query} с {@code nativeQuery} — запрос на SQL
     * PostgreSQL, Spring Data превращает строки в {@link Source}.
     * https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html
     *
     * @param readAfterSeconds через сколько секунд после начала последнего чтения источник читается снова
     * @return источники к чтению, сначала не читавшиеся и читавшиеся давнее
     */
    @Query(nativeQuery = true, value = TO_READ + BY_LAST_READ)
    List<Source> findToRead(double readAfterSeconds);

    /**
     * Незакрытые источники провайдера ({@link #OPEN}) без учёта времени прошлого чтения — источникам государственного
     * портала ими записываются вакансии из общего списка (§107, §111).
     *
     * @param provider провайдер
     * @return источники провайдера по id
     */
    @Query(nativeQuery = true, value = OPEN + " AND s.provider = ?1 ORDER BY s.id")
    List<Source> findOpenByProvider(String provider);

    /**
     * Следующий источник провайдера с общим хостом, которому пора читаться ({@link #TO_READ}) и по которому нет
     * незавершённого чтения (в очереди, выполняется, ждёт повтора; не менявшееся больше суток — брошено, как
     * {@code TaskService#activeKeys}). Ключ задания чтения — {@code read:<источник>:<час>}.
     *
     * @param readAfterSeconds через сколько секунд после начала последнего чтения источник читается снова
     * @param provider         провайдер с общим хостом
     * @return источник, если есть
     */
    @Query(nativeQuery = true, value = TO_READ + """
              AND s.provider = ?2
              AND NOT EXISTS (SELECT 1 FROM task t
                              WHERE t.type = 'READ_SOURCE' AND t.state IN ('QUEUED', 'RUNNING', 'WAITING')
                                AND t.updated_at > now() - interval '1 day'
                                AND t.task_key LIKE 'read:' || s.id || ':%')
            """ + BY_LAST_READ + " LIMIT 1")
    Optional<Source> findNextToRead(double readAfterSeconds, String provider);

    /**
     * Провайдеры, у которых чтение источника в очереди или выполняется (не менявшееся больше суток — брошено).
     * Чтение, ждущее повтора, не в счёт: повтор не держит цепочку чтений общего хоста (§103).
     *
     * @return провайдеры
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT s.provider FROM task t JOIN source s ON s.id = CAST(t.payload ->> 'sourceId' AS BIGINT)
            WHERE t.type = 'READ_SOURCE' AND t.state IN ('QUEUED', 'RUNNING')
              AND t.updated_at > now() - interval '1 day'
            """)
    List<String> providersReading();

    /**
     * Нужно ли источнику название работодателя от системы найма: он не связан ни с одной компанией (доска обратного
     * пути, §27, §34) и название ещё не записано (аудит §65). Правило то же, что у API сведений о вакансии: источник
     * кадрового агентства название работодателя не получает (аудит §66).
     *
     * @param sourceId источник
     * @return {@code true} — запросить название
     */
    @Query(nativeQuery = true, value = """
            SELECT s.employer_name IS NULL AND NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.source_id = s.id)
            FROM source s WHERE s.id = ?1
            """)
    boolean needsEmployerName(Long sourceId);

    /**
     * Записывает название работодателя источника, если его ещё нет.
     *
     * @param sourceId источник
     * @param name     название, как его даёт система найма
     * @return 1 — записано, 0 — уже было
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = "UPDATE source SET employer_name = ?2 WHERE id = ?1 AND employer_name IS NULL")
    int nameEmployer(Long sourceId, String name);

    /**
     * Записывает название работодателя, заданное вручную ({@link ManualEmployerNames}, §123): заменяет название от
     * системы найма. Источник ищется по провайдеру и доске (уникальная пара); его ещё нет — ничего не записывается.
     *
     * @param provider провайдер
     * @param board    доска, как записана в источнике
     * @param name     название
     * @return 1 — записано, 0 — источника нет или название то же
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            UPDATE source SET employer_name = ?3
            WHERE provider = ?1 AND board = ?2 AND employer_name IS DISTINCT FROM ?3
            """)
    int nameEmployerManually(String provider, String board, String name);
}
