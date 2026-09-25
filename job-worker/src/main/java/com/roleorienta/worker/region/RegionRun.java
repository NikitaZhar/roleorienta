package com.roleorienta.worker.region;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.region.RegistryCompanyStore.Details;
import com.roleorienta.worker.region.RegistryCompanyStore.Imported;
import com.roleorienta.worker.region.RegistryCompanyStore.Pending;
import com.roleorienta.worker.region.RpoClient.Activity;
import com.roleorienta.worker.region.RpoClient.Hit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Проход пути «от региона и профиля» (план R1, R7; §95) под leader-lock: (1) импорт IT-компаний
 * Словакии из реестра RPO, пока в выборке меньше {@code import-limit} (не больше
 * {@code lookups-per-pass} карточек за проход); (2) проверка
 * {@code check-batch} ещё не проверенных компаний: сайт по названию и номеру юрлица
 * ({@link SiteLocator}), карьерная страница и её тип ({@link CareerPageInspector}).
 *
 * <p>Сетевые запросы идут внутри транзакции лока — как у проверки покрытия; бюджеты малы
 * (выборка — десятки компаний). Сбой одной компании не останавливает проход: при импорте
 * карточка пропускается (после {@link #MAX_FAILED_LOOKUPS} отказов подряд импорт прохода
 * прерывается), при проверке — итог «ошибка» с причиной, компания считается проверенной.</p>
 */
@Component
public class RegionRun {

    /** Ключ advisory-лока (реестр ключей — {@link PostgresLeaderLock}). */
    static final long REGION_LOCK_KEY = 1008L;

    /** Страна пути (первая страна — Словакия, ADR-19). */
    static final String COUNTRY = "SK";

    /** Реестр юрлиц страны. */
    static final String REGISTRY = "RPO";

    /**
     * Сколько отказов карточки реестра подряд прерывают импорт прохода (стенд §96: карточки RPO
     * отвечают дольше тайм-аута чтения) — проход переходит к проверке, импорт продолжится позже.
     */
    static final int MAX_FAILED_LOOKUPS = 3;

    private static final Logger log = LoggerFactory.getLogger(RegionRun.class);

    private final RegionProperties properties;
    private final RpoClient registry;
    private final RegistryCompanyStore store;
    private final CompanyCheck check;
    private final PostgresLeaderLock leaderLock;

    /**
     * @param properties бюджеты и параметры поиска
     * @param registry   реестр юрлиц
     * @param store      компании региона
     * @param check      проверка сайта и карьерной страницы
     * @param leaderLock leader-lock прохода
     */
    public RegionRun(RegionProperties properties, RpoClient registry, RegistryCompanyStore store,
                     CompanyCheck check, PostgresLeaderLock leaderLock) {
        this.properties = properties;
        this.registry = registry;
        this.store = store;
        this.check = check;
        this.leaderLock = leaderLock;
    }

    /**
     * Один проход под leader-lock.
     *
     * @return {@code true}, если эта реплика была лидером
     */
    public boolean run() {
        return leaderLock.runIfLeader(REGION_LOCK_KEY, this::runOnce);
    }

    /** Импорт до лимита выборки, затем проверка пачки. */
    void runOnce() {
        int imported = importCompanies();
        int checked = 0;
        for (Pending company : store.unchecked(properties.checkBatch())) {
            CheckResult result = check.check(company);
            store.recordCheck(company.id(), result);
            checked++;
            log.info("Регион: {} ({}) — {}", company.name(), company.registryId(), result.note());
        }
        log.info("Регион: импортировано {}, проверено {}", imported, checked);
    }

    private int importCompanies() {
        int room = properties.importLimit() - store.count(COUNTRY);
        int added = 0;
        int lookups = 0;
        int failedInRow = 0;
        for (String term : properties.rpo().activityTerms()) {
            for (String municipality : properties.rpo().municipalities()) {
                if (added >= room) {
                    return added;
                }
                for (Hit hit : registry.search(term, municipality)) {
                    if (added >= room || lookups >= properties.lookupsPerPass() || failedInRow >= MAX_FAILED_LOOKUPS) {
                        return added;
                    }
                    if (store.exists(COUNTRY, REGISTRY, hit.ico())) {
                        continue;
                    }
                    lookups++;
                    Optional<Activity> activity;
                    try {
                        activity = registry.mainActivity(hit.id());
                        failedInRow = 0;
                    } catch (RuntimeException failure) {
                        failedInRow++;
                        log.warn("Регион: карточка RPO {} ({}) не прочитана: {}", hit.id(), hit.name(), failure.getMessage());
                        continue;
                    }
                    if (activity.isPresent() && inProfile(activity.get().code())
                            && store.add(new Imported(COUNTRY, REGISTRY, hit.ico(), hit.name(),
                                    new Details(hit.municipality(), activity.get().code(), activity.get().value())))) {
                        added++;
                    }
                }
            }
        }
        return added;
    }

    private boolean inProfile(String activityCode) {
        return properties.rpo().codePrefixes().stream().anyMatch(activityCode::startsWith);
    }
}
