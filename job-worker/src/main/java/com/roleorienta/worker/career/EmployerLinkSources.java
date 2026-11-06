package com.roleorienta.worker.career;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.vacancy.VacancyMergeRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Источник компании по ссылке на вакансию у работодателя (§266): кнопка отклика объявления profesia.sk ведёт на
 * вакансию работодателя (§209); если адрес — доска системы найма с адаптером ({@link CareerLinks#board}: Workday,
 * Greenhouse, SmartRecruiters, Nalgoo, Oracle, Personio), доска подключается источником компании объявления — её
 * вакансии читает адаптер, сайт компании для этого не нужен (Accenture: сайт accenture.com программе не отвечает, а
 * кнопка ведёт на Workday {@code accenture.wd103.myworkdayjobs.com/AccentureCareers}). Ссылка на собственный сайт
 * работодателя этим шагом не используется. Затем в том же проходе объявления площадок склеиваются с вакансиями
 * систем найма по той же ссылке ({@link VacancyMergeRepository#mergeByEmployerLinks}, §268): одна вакансия — одна
 * запись, ссылка — на страницу работодателя.
 *
 * <p>Проход — раз в час под leader-lock (ключ {@value #LOCK_KEY}): все ссылки открытых публикаций и живых
 * объявлений ({@link CareerScanRepository#employerLinks}); подключение повторно ничего не дублирует. Провайдер без
 * основания использования или агентство без разрешения ({@link CareerScanRepository#permittedRole}) — не
 * подключается. HTTP нет: доска определяется по адресу. Выключается свойством
 * {@code app.employer-links.enabled=false}.</p>
 */
@Component
@ConditionalOnProperty(name = "app.employer-links.enabled", havingValue = "true", matchIfMissing = true)
public class EmployerLinkSources {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1023;

    private static final Logger LOG = LoggerFactory.getLogger(EmployerLinkSources.class);

    private final PostgresLeaderLock leaderLock;
    private final CareerScanRepository sources;
    private final VacancyMergeRepository merges;

    /**
     * @param leaderLock leader-lock
     * @param sources    ссылки, основания использования, подключение источников
     * @param merges     склейка объявлений с вакансиями систем найма
     */
    public EmployerLinkSources(PostgresLeaderLock leaderLock, CareerScanRepository sources,
            VacancyMergeRepository merges) {
        this.leaderLock = leaderLock;
        this.sources = sources;
        this.merges = merges;
    }

    /**
     * Тик: первый — сразу после запуска, затем через {@code app.employer-links.interval-ms} после окончания прошлого.
     */
    @Scheduled(fixedDelayString = "${app.employer-links.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, this::connectAll);
            // Отдельная транзакция (аудит §268): сбой склейки не откатывает подключение досок.
            leaderLock.runIfLeader(LOCK_KEY, () -> LOG.info(
                    "Vacancies merged into recruiting system vacancies by employer link: {}",
                    merges.mergeByEmployerLinks()));
        } catch (DataAccessException exception) {
            LOG.warn("Sources from employer links failed, will retry on next tick", exception);
        }
    }

    /**
     * Подключает доски систем найма из ссылок на вакансию у работодателя.
     *
     * @return у скольких компаний есть доска из ссылки (подключена сейчас или раньше)
     */
    public int connectAll() {
        Set<String> providers = sources.permittedProviders();
        Map<Long, Set<Board>> boards = new HashMap<>();
        for (CareerScanRepository.EmployerLink link : sources.employerLinks()) {
            CareerLinks.board(link.url()).filter(board -> providers.contains(board.provider()))
                    .ifPresent(board -> boards.computeIfAbsent(link.companyId(), company -> new HashSet<>())
                            .add(board));
        }
        int companies = 0;
        for (Map.Entry<Long, Set<Board>> company : boards.entrySet()) {
            Optional<CareerScanRepository.Role> role = sources.permittedRole(company.getKey());
            if (role.isPresent()) {
                sources.connectFromEmployerLink(company.getKey(), company.getValue(), role.get());
                companies++;
            }
        }
        LOG.info("Sources from employer links: {} companies with a recruiting system board", companies);
        return companies;
    }
}
