package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Пересмотр «одна вакансия — одна запись» раз в час (docs/vacancy-identity-plan.md §5, срез 3; стенограмма §280):
 * партии {@link VacancyMergeRepository#mergeBatch} по {@code app.vacancy-merge.batch} вакансий, каждая — своя
 * транзакция под leader-lock (ключ {@value #LOCK_KEY}), пока партия не окажется неполной. Первый проход после
 * запуска склеивает накопленное. Выключается свойством {@code app.vacancy-merge.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.vacancy-merge.enabled", havingValue = "true", matchIfMissing = true)
public class VacancyMergeTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1026;

    private static final Logger LOG = LoggerFactory.getLogger(VacancyMergeTick.class);

    private final PostgresLeaderLock leaderLock;
    private final VacancyMergeRepository merges;
    private final int batch;

    /**
     * @param leaderLock leader-lock
     * @param merges     пересмотр вакансий
     * @param batch      сколько вакансий присоединять за партию
     */
    public VacancyMergeTick(PostgresLeaderLock leaderLock, VacancyMergeRepository merges,
            @Value("${app.vacancy-merge.batch:200}") int batch) {
        this.leaderLock = leaderLock;
        this.merges = merges;
        this.batch = batch;
    }

    /**
     * Тик: первый — сразу после запуска, затем через {@code app.vacancy-merge.interval-ms} после окончания прошлого.
     */
    @Scheduled(fixedDelayString = "${app.vacancy-merge.interval-ms:3600000}")
    public void tick() {
        int total = 0;
        try {
            int[] merged = {batch};
            while (merged[0] == batch) {
                merged[0] = 0;
                if (!leaderLock.runIfLeader(LOCK_KEY, () -> merged[0] = merges.mergeBatch(batch))) {
                    return;
                }
                total += merged[0];
            }
        } catch (DataAccessException exception) {
            LOG.warn("Vacancy merge failed after {} merged, will retry on next tick", total, exception);
            return;
        }
        LOG.info("Vacancies merged by identity: {}", total);
    }
}
