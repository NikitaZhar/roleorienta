package com.roleorienta.worker.lock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leader-lock на advisory-блокировке PostgreSQL: защищённую работу в каждый момент выполняет одна
 * реплика worker (технический документ §16.4).
 *
 * <p>{@code pg_try_advisory_xact_lock} — транзакционная блокировка: не ждёт, если занята, и сама
 * освобождается при завершении транзакции, поэтому не может «зависнуть» после ошибки. Метод
 * транзакционный — блокировка и работа идут в одной транзакции.
 * https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS</p>
 *
 * <p>Это защита от одновременного выполнения, а не гарантия единственности навсегда; последняя
 * защита от дублей — идемпотентность и уникальные ключи.</p>
 *
 * <p><b>Реестр ключей</b> (новый ключ — следующий номер, сюда же): 1001 — перепостановка заданий
 * ({@code TaskRequeueTick}); 1002 — постановка чтения источников ({@code ReadSourceTick}); 1003 — постановка приёма реестра
 * ({@code RegistryIntakeTick}); 1004 — постановка скана сайтов ({@code SiteScanTick}); 1005 — постановка проверки
 * сайтов на кадровые страницы ({@code CareerScanTick}); 1006 — постановка обратного пути
 * ({@code BoardDiscoveryTick}); 1007 — постановка предрасчёта соответствий ({@code MatchVacanciesTick});
 * 1008 — постановка загрузки справочника GeoNames ({@code GeoImportTick}); 1009 — планирование
 * прохода выдачи ({@code PassTick}); 1010 — постановка работы с государственным порталом ({@code StatePortalTick}); 1011 — постановка шага
 * «Wikidata» поиска сайта ({@code WikidataSiteTick}); 1012 — постановка шага «адрес по названию»
 * ({@code SiteNameTick}); 1013 — постановка шага «число сотрудников из RÚZ» ({@code CompanySizeTick}); 1014 —
 * лента и полный обход общего списка портала ({@code PortalListTick}); 1015 — следующее чтение провайдеров с общим
 * хостом ({@code ReadSourceTick#sharedHostTick}); 1016 — постановка удаления снимков по сроку
 * ({@code SnapshotPurgeTick}); 1017 — постановка расчёта показателей ядра ({@code ComputeMetricsTick}); 1018 —
 * постановка проверки адресов кадровых страниц пользователей ({@code CareerRequestTick}); 1019 — постановка сводок
 * на почту ({@code DigestTick}); 1020 — постановка пересчёта профессии по названию ({@code TitleOccupationTick}); 1021 —
 * пересчёт полноты сбора по компаниям ({@code CoverageTick}); 1022 — постановка распознавания позиции по названию
 * ({@code TitleClassificationTick}); 1023 — источники по ссылке на вакансию у работодателя
 * ({@code EmployerLinkSources}); 1024 — постановка чтения позиций profesia.sk ({@code ProfesiaPositionsTick}); 1025 — не leader-lock, а
 * блокировка записи вакансий ({@link #VACANCY_WRITE}, §271); 1026 — пересмотр «одна вакансия — одна запись»
 * ({@code VacancyMergeTick}, §280).</p>
 */
@Component
public class PostgresLeaderLock {

    /**
     * Ключ блокировки записи вакансий (§271, аудит §270 замечание 2): разделяемую ({@link #VACANCY_WRITE_SHARED}) берут
     * транзакции, которые пишут публикации и ссылаются на вакансии (запись чтения источника, выдача, сводка на почту), —
     * друг другу они не мешают; исключительную ({@link #VACANCY_WRITE_EXCLUSIVE}) — склейка вакансий, которая переносит
     * публикации и удаляет вакансии: она ждёт окончания идущих записей, новые ждут её. Без этого запись, прочитавшая
     * публикацию до склейки, пишет обратно удалённую вакансию (Hibernate обновляет все колонки) — откат чтения
     * источника. Транзакционная: освобождается с концом транзакции. Берётся первым запросом транзакции.
     */
    public static final long VACANCY_WRITE = 1025;

    /** Разделяемая блокировка записи вакансий ({@link #VACANCY_WRITE}). */
    public static final String VACANCY_WRITE_SHARED = "SELECT 1 FROM pg_advisory_xact_lock_shared(" + VACANCY_WRITE + ")";

    /** Исключительная блокировка записи вакансий ({@link #VACANCY_WRITE}) — склейка. */
    public static final String VACANCY_WRITE_EXCLUSIVE = "SELECT 1 FROM pg_advisory_xact_lock(" + VACANCY_WRITE + ")";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public PostgresLeaderLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Выполняет работу, если блокировка с этим ключом свободна.
     *
     * @param lockKey ключ блокировки роли (реестр — в описании класса)
     * @param work    работа; выполняется в транзакции этого метода
     * @return {@code true} — блокировка захвачена и работа выполнена; {@code false} — занята
     */
    @Transactional
    public boolean runIfLeader(long lockKey, Runnable work) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey);
        if (Boolean.TRUE.equals(acquired)) {
            work.run();
            return true;
        }
        return false;
    }
}
