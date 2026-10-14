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
     * Один канал на компанию (общая часть запросов): все источники, кроме источника канала ниже кадровой страницы
     * (государственный портал, {@code ?1}), если у его компании есть другой источник — у компании читается один канал
     * (бизнес-описание §4.1); кадровый сайт группы (роль {@code GROUP}, §81) портал не отключает.
     */
    String ONE_CHANNEL = """
            SELECT s.* FROM source s
            WHERE (s.provider <> ?1
                   OR NOT EXISTS (SELECT 1 FROM company_source own
                                  JOIN company_source other ON other.company_id = own.company_id
                                                               AND other.role <> 'GROUP'
                                  JOIN source o ON o.id = other.source_id
                                  WHERE own.source_id = s.id AND o.provider <> ?1))
            """;

    /**
     * Источники к чтению (общая часть запросов {@link #findToRead} и {@link #findNextToRead}): {@link #ONE_CHANNEL} и
     * только те, чьё последнее чтение (обход {@code crawl_run}) началось раньше, чем {@code ?2} секунд назад, или
     * которых ещё не читали — чтение не реже раза в сутки по времени, а не по дате (§103).
     */
    String TO_READ = ONE_CHANNEL + """
              AND NOT EXISTS (SELECT 1 FROM crawl_run r
                              WHERE r.source_id = s.id AND r.started_at > now() - make_interval(secs => ?2))
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
     * @param channelProvider    провайдер канала ниже кадровой страницы
     * @param readAfterSeconds   через сколько секунд после начала последнего чтения источник читается снова
     * @return источники к чтению, сначала не читавшиеся и читавшиеся давнее
     */
    @Query(nativeQuery = true, value = TO_READ + BY_LAST_READ)
    List<Source> findToRead(String channelProvider, double readAfterSeconds);

    /**
     * Источники канала ниже кадровой страницы (государственный портал) с правилом «один канал на компанию»
     * ({@link #ONE_CHANNEL}), без учёта времени прошлого чтения — им записываются вакансии из общего списка портала
     * (§107, §111).
     *
     * @param channelProvider провайдер канала ниже кадровой страницы
     * @return источники этого провайдера к записи
     */
    @Query(nativeQuery = true, value = ONE_CHANNEL + " AND s.provider = ?1 ORDER BY s.id")
    List<Source> findChannelSources(String channelProvider);

    /**
     * Следующий источник провайдера с общим хостом, которому пора читаться ({@link #TO_READ}) и по которому нет
     * незавершённого чтения (в очереди, выполняется, ждёт повтора; не менявшееся больше суток — брошено, как
     * {@code TaskService#activeKeys}). Ключ задания чтения — {@code read:<источник>:<час>}.
     *
     * @param channelProvider  провайдер канала ниже кадровой страницы
     * @param readAfterSeconds через сколько секунд после начала последнего чтения источник читается снова
     * @param provider         провайдер с общим хостом
     * @return источник, если есть
     */
    @Query(nativeQuery = true, value = TO_READ + """
              AND s.provider = ?3
              AND NOT EXISTS (SELECT 1 FROM task t
                              WHERE t.type = 'READ_SOURCE' AND t.state IN ('QUEUED', 'RUNNING', 'WAITING')
                                AND t.updated_at > now() - interval '1 day'
                                AND t.task_key LIKE 'read:' || s.id || ':%')
            """ + BY_LAST_READ + " LIMIT 1")
    Optional<Source> findNextToRead(String channelProvider, double readAfterSeconds, String provider);

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
}
