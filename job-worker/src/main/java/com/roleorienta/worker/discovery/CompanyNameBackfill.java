package com.roleorienta.worker.discovery;

import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.CompanySource;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.BoardProfile;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Имена компаний, заведённых до §80 с именем-slug ({@code lilly/LLY}) (§83).
 *
 * <p>Раз в период под leader-lock берёт до {@value #BATCH} таких компаний и даёт им имя так же,
 * как новым (A3): сведения о доске от провайдера ({@code SourceAdapter.boardProfile}) и то
 * написание владельца, которое находит {@link BoardOwnershipProperties#ownerName}. Имени в описании
 * нет (или провайдер сведений не даёт, или страницы доски нет — 4xx) — имя = владелец из slug
 * (часть до {@code '/'}): без {@code '/'} компания больше не выбирается, проход не зацикливается на
 * тех, кого переименовать нельзя. Временный сбой — компания остаётся со slug и берётся снова.</p>
 *
 * <p>Включается {@code app.discovery.name-backfill.enabled=true}; в тестах выключен (проход —
 * {@link #run()} напрямую).</p>
 */
@Component
@ConditionalOnProperty(name = "app.discovery.name-backfill.enabled", havingValue = "true")
public class CompanyNameBackfill {

    /** Ключ advisory-лока (реестр ключей — {@link PostgresLeaderLock}). */
    static final long NAME_BACKFILL_LOCK_KEY = 1006L;

    /** Компаний за проход: по одному запросу страницы доски на каждую. */
    static final int BATCH = 10;

    private static final Logger log = LoggerFactory.getLogger(CompanyNameBackfill.class);

    private final CompanySourceRepository companySources;
    private final SourceAdapterRegistry adapters;
    private final BoardOwnershipProperties ownership;
    private final PostgresLeaderLock leaderLock;

    /**
     * @param companySources связи компаний с досками
     * @param adapters       адаптеры провайдеров (сведения о доске)
     * @param ownership      поиск имени владельца в описании доски
     * @param leaderLock     leader-lock прохода
     */
    public CompanyNameBackfill(CompanySourceRepository companySources, SourceAdapterRegistry adapters,
                               BoardOwnershipProperties ownership, PostgresLeaderLock leaderLock) {
        this.companySources = companySources;
        this.adapters = adapters;
        this.ownership = ownership;
        this.leaderLock = leaderLock;
    }

    /** Тик: один проход; ошибка логируется, повтор — на следующем тике. */
    @Scheduled(fixedDelayString = "${app.discovery.name-backfill.poll-interval-ms:3600000}")
    public void tick() {
        try {
            run();
        } catch (RuntimeException exception) {
            log.warn("Имена компаний: проход завершился ошибкой, повтор на следующем тике", exception);
        }
    }

    /**
     * Один проход под leader-lock.
     *
     * @return {@code true}, если эта реплика была лидером
     */
    public boolean run() {
        return leaderLock.runIfLeader(NAME_BACKFILL_LOCK_KEY, this::renameBatch);
    }

    /** Переименовывает до {@link #BATCH} компаний (у компании с несколькими досками — по первой). */
    void renameBatch() {
        List<CompanySource> links = companySources.withSlugNames(Limit.of(BATCH));
        Set<Long> done = new HashSet<>();
        for (CompanySource link : links) {
            Company company = link.getCompany();
            if (done.add(company.getId())) {
                resolveName(link.getSource()).ifPresent(name -> {
                    log.info("Имена компаний: {} → {}", company.getName(), name);
                    company.setName(name);
                });
            }
        }
    }

    /**
     * Имя по доске: из описания, иначе владелец из slug; пусто — временный сбой, повторить позже.
     */
    private Optional<String> resolveName(Source source) {
        String owner = ownerOf(source.getExternalRef());
        try {
            Optional<BoardProfile> profile = adapters.forProviderCode(source.getProvider().getCode())
                    .boardProfile(source);
            return Optional.of(profile.flatMap(ownership::ownerName).orElse(owner));
        } catch (HttpClientErrorException exception) {
            if (exception instanceof HttpClientErrorException.TooManyRequests) {
                log.warn("Имена компаний: доска {} — 429, повтор позже", source.getExternalRef());
                return Optional.empty();
            }
            return Optional.of(owner); // страницы доски нет — имя из описания не получить
        } catch (RuntimeException exception) {
            log.warn("Имена компаний: доска {} не ответила, повтор позже: {}", source.getExternalRef(), exception.getMessage());
            return Optional.empty();
        }
    }

    /** Владелец доски из slug: часть до {@code '/'}. */
    static String ownerOf(String slug) {
        int slash = slug.indexOf('/');
        return slash >= 0 ? slug.substring(0, slash) : slug;
    }
}
