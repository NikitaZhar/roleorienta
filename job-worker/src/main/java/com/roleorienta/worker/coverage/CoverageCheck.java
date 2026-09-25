package com.roleorienta.worker.coverage;

import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.CoverageAssessment;
import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.coverage.CoverageMatcher.Verdict;
import com.roleorienta.worker.coverage.KarriereClient.Listings;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Проход проверки покрытия площадкой (A5, §82): под leader-lock берёт до
 * {@code maxCompaniesPerPass} работодателей с австрийскими публикациями без свежей оценки,
 * по одному запросу к karriere.at на работодателя (и по одному на каждую его доску другого бренда,
 * §86), и записывает оценку каждой публикации
 * ({@link CoverageMatcher}).
 *
 * <p>Сбой запроса или разбора у работодателя — оценки не трогаются (публикации остаются
 * «не проверено» или со старой оценкой) и работодатель берётся снова в следующем проходе.
 * HTTP-запросы идут внутри транзакции лока — как у обработчиков сбора на пилоте; бюджет прохода
 * мал (по умолчанию 3 запроса с интервалом темпа).</p>
 */
@Component
public class CoverageCheck {

    /** Ключ advisory-лока (реестр ключей — {@link PostgresLeaderLock}). */
    static final long COVERAGE_LOCK_KEY = 1005L;

    private static final Logger log = LoggerFactory.getLogger(CoverageCheck.class);

    private final CoverageProperties properties;
    private final CoverageTargetRepository targets;
    private final CoverageAssessmentRepository assessments;
    private final KarriereClient platform;
    private final PostgresLeaderLock leaderLock;

    /**
     * @param properties  бюджет и период перепроверки
     * @param targets     работодатели и публикации к проверке
     * @param assessments оценки покрытия
     * @param platform    клиент площадки
     * @param leaderLock  leader-lock прохода
     */
    public CoverageCheck(CoverageProperties properties, CoverageTargetRepository targets,
                         CoverageAssessmentRepository assessments, KarriereClient platform,
                         PostgresLeaderLock leaderLock) {
        this.properties = properties;
        this.targets = targets;
        this.assessments = assessments;
        this.platform = platform;
        this.leaderLock = leaderLock;
    }

    /**
     * Один проход под leader-lock.
     *
     * @return {@code true}, если эта реплика была лидером
     */
    public boolean run() {
        return leaderLock.runIfLeader(COVERAGE_LOCK_KEY, () -> checkDue(Instant.now()));
    }

    /**
     * Проверяет работодателей без свежей оценки.
     *
     * @param now момент проверки (дата в причине, граница «свежести»)
     */
    void checkDue(Instant now) {
        List<Company> due = targets.companiesDue(KarriereClient.COUNTRY,
                now.minus(properties.recheckAfterHours(), ChronoUnit.HOURS),
                Limit.of(properties.maxCompaniesPerPass()));
        String checkedOn = now.atOffset(ZoneOffset.UTC).toLocalDate().toString();
        for (Company company : due) {
            checkCompany(company, now, checkedOn);
        }
    }

    /**
     * Проверка одного работодателя: публикации группируются по имени для поиска
     * ({@link #searchName}) — у досок другого бренда (§86) свой запрос к площадке.
     */
    private void checkCompany(Company company, Instant now, String checkedOn) {
        Map<String, List<JobPosting>> bySearchName = targets.postingsOf(company, KarriereClient.COUNTRY)
                .stream()
                .collect(Collectors.groupingBy(posting -> searchName(company, posting.getSource()),
                        LinkedHashMap::new, Collectors.toList()));
        bySearchName.forEach((employer, postings) -> checkEmployer(employer, postings, now, checkedOn));
    }

    /**
     * Один запрос к площадке и оценки публикаций. Площадка ответила 404 (так karriere.at отвечает
     * на слово поиска, по которому у неё нет выдачи; стенд §82: «dxctechnology») — публикации
     * получают {@code UNKNOWN} с причиной и датой: работодатель не занимает очередь до
     * перепроверки. Прочие сбои — оценки не трогаются, работодатель берётся снова.
     */
    private void checkEmployer(String employer, List<JobPosting> postings, Instant now, String checkedOn) {
        Listings listings;
        try {
            listings = platform.activeListings(employer);
        } catch (HttpClientErrorException.NotFound exception) {
            Verdict noResults = new Verdict(CoverageState.UNKNOWN, "на " + KarriereClient.PLATFORM
                    + " нет выдачи по «" + employer + "» при проверке " + checkedOn);
            postings.forEach(posting -> record(posting, noResults, now));
            log.info("Покрытие: {} — на {} нет выдачи, публикаций {} → не проверено",
                    employer, KarriereClient.PLATFORM, postings.size());
            return;
        } catch (RuntimeException exception) {
            log.warn("Покрытие: {} — площадка не ответила или страница не разобрана, повтор позже: {}",
                    employer, exception.getMessage());
            return;
        }
        int hidden = 0;
        for (JobPosting posting : postings) {
            Verdict verdict = CoverageMatcher.verdict(posting.getRawTitle(), employer, listings, checkedOn);
            record(posting, verdict, now);
            hidden += verdict.state() == CoverageState.SITE_ONLY ? 1 : 0;
        }
        log.info("Покрытие: {} — на {} активных {}, наших публикаций {}, только с сайта {}",
                employer, KarriereClient.PLATFORM, listings.items().size(), postings.size(), hidden);
    }

    private void record(JobPosting posting, Verdict verdict, Instant now) {
        CoverageAssessment assessment = assessments.findByPosting_Id(posting.getId()).orElseGet(() -> {
            CoverageAssessment created = new CoverageAssessment();
            created.setPosting(posting);
            return created;
        });
        assessment.setState(verdict.state());
        assessment.setReason(verdict.reason());
        assessment.setCheckedPlatforms(KarriereClient.PLATFORM);
        assessment.setCheckedAt(now);
        assessment.setMatcherVersion(CoverageMatcher.VERSION);
        assessments.save(assessment);
    }

    /**
     * Имя для поиска публикаций доски: бренд доски, если он отличается от работодателя
     * ({@link BoardBrand}, §86), иначе {@link #searchName(Company)}.
     *
     * @param company работодатель
     * @param source  доска публикации; {@code null} — искать по имени компании
     * @return слово поиска на площадке
     */
    static String searchName(Company company, Source source) {
        return Optional.ofNullable(source)
                .flatMap(board -> BoardBrand.of(board.getExternalRef(), company.getName()))
                .orElseGet(() -> searchName(company));
    }

    /**
     * Имя для поиска: имя компании; у компаний, заведённых до §80 с именем-slug
     * ({@code lilly/LLY}), — часть до {@code '/'}.
     */
    static String searchName(Company company) {
        String name = company.getName();
        int slash = name.indexOf('/');
        return slash >= 0 ? name.substring(0, slash) : name;
    }
}
