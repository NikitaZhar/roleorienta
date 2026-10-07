package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.crawl.CrawlLog;
import com.roleorienta.worker.source.Source;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Записывает результат чтения источника и пересчитывает состояние затронутых вакансий
 * (бизнес-описание §4.3).
 *
 * <ul>
 *   <li>Полное чтение: новая публикация — новая вакансия; известная — обновление сведений и
 *       подтверждение; отсутствующая — счётчик отсутствия, закрытие после
 *       {@link VacancyProperties#closeAfterMissingReads()} полных чтений подряд.</li>
 *   <li>Неполное чтение: добавляются только новые публикации (они подтверждены); сохранённые не
 *       меняются и не закрываются, но теряют подтверждение — их вакансии нуждаются в повторной
 *       проверке (технический документ §4).</li>
 *   <li>Ссылка публикации не http(s) (пустая, {@code javascript:} и т. п.; аудит §66): новая публикация не
 *       записывается, известная теряет подтверждение, но не меняется и не закрывается — ссылка уходит в
 *       сведения о вакансии и в SPA.</li>
 *   <li>Отказ источника: сведения не меняются, подтверждения нет — вакансии источника нуждаются в
 *       повторной проверке.</li>
 * </ul>
 *
 * <p>Обход ({@link CrawlRun}) и доступность источника ({@link CrawlLog}) сохраняются в той же
 * транзакции, что и изменения публикаций, — обход
 * первым: на него ссылается история вакансий ({@link VacancyRevision}), которую пишет только
 * полное чтение.</p>
 */
@Service
public class PostingRecorder {

    /**
     * Ссылка http(s) с хостом. Только начало адреса: пробел или иной недопустимый для {@code java.net.URI} знак дальше
     * в пути (адрес как есть со страницы) ссылку не отменяет.
     */
    private static final Pattern WEB_URL = Pattern.compile("(?i)https?://[^/?#\\s:@][^/?#\\s]*(?:[/?#].*)?",
            Pattern.DOTALL);
    private static final Logger LOG = LoggerFactory.getLogger(PostingRecorder.class);

    private final JobPostingRepository postings;
    private final VacancyRepository vacancies;
    private final CrawlLog crawlLog;
    private final VacancyProperties properties;
    private final Clock clock;

    /**
     * @param postings   доступ к публикациям
     * @param vacancies  доступ к вакансиям
     * @param crawlLog   итог обхода и доступность источника
     * @param properties настройки жизненного цикла
     * @param clock      часы
     */
    public PostingRecorder(JobPostingRepository postings, VacancyRepository vacancies, CrawlLog crawlLog,
            VacancyProperties properties, Clock clock) {
        this.postings = postings;
        this.vacancies = vacancies;
        this.crawlLog = crawlLog;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Начинает обход: время начала — до первого запроса к источнику.
     *
     * @param source источник
     * @param taskId задание чтения
     * @return обход без итога; в БД ещё не записан
     */
    public CrawlRun startRun(Source source, long taskId) {
        return CrawlRun.start(source, taskId, clock.instant());
    }

    /**
     * Записывает публикации одного чтения источника и его обход в одной транзакции.
     *
     * @param run        обход {@code COMPLETE} или {@code PARTIAL}, ещё не сохранённый
     * @param fetched    публикации из источника
     * @param unverified внешние id пропавших публикаций, отсутствие которых проверить не удалось:
     *                   теряют подтверждение, но отсутствие не засчитывается
     */
    @Transactional
    public void record(CrawlRun run, List<FetchedPosting> fetched, Set<String> unverified) {
        Instant now = clock.instant();
        crawlLog.finish(run, now);
        Source source = run.getSource();
        boolean complete = run.isComplete();
        Map<String, JobPosting> known = postings.findBySourceId(source.getId()).stream()
                .collect(Collectors.toMap(JobPosting::getExternalId, Function.identity()));
        Set<Long> touched = new HashSet<>();
        Set<String> notVerified = new HashSet<>(unverified);
        for (FetchedPosting posting : fetched) {
            if (!isWebUrl(posting.url())) {
                LOG.warn("Source {}: posting {} skipped, link is not http(s): {}", source.getId(),
                        posting.externalId(), posting.url());
                notVerified.add(posting.externalId());
                continue;
            }
            JobPosting existing = known.remove(posting.externalId());
            if (existing == null) {
                Vacancy vacancy = vacancies.save(new Vacancy(posting.title(), posting.url(), now));
                postings.save(new JobPosting(source, vacancy, posting, now));
            } else if (complete) {
                existing.update(posting, run, now);
                existing.getVacancy().confirm(posting.title(), posting.url(), now);
                touched.add(existing.getVacancy().getId());
            } else {
                existing.markUnconfirmed();
                touched.add(existing.getVacancy().getId());
            }
        }
        for (JobPosting missing : known.values()) {
            if (complete && !notVerified.contains(missing.getExternalId())) {
                missing.markMissing(properties.closeAfterMissingReads(), now);
            } else {
                missing.markUnconfirmed();
            }
            touched.add(missing.getVacancy().getId());
        }
        refreshStates(touched, now);
    }

    /**
     * @param url ссылка публикации
     * @return {@code true} — адрес http(s) с хостом
     */
    static boolean isWebUrl(String url) {
        return url != null && WEB_URL.matcher(url).matches();
    }

    /**
     * @param source источник
     * @return внешние id публикаций источника, текст которых уже получен
     */
    @Transactional(readOnly = true)
    public Set<String> externalIdsWithContent(Source source) {
        return new HashSet<>(postings.findExternalIdsWithContent(source.getId()));
    }

    /**
     * @param source источник
     * @return внешние id незакрытых публикаций источника
     */
    @Transactional(readOnly = true)
    public List<String> openExternalIds(Source source) {
        return postings.findOpenExternalIds(source.getId());
    }

    /**
     * Источник не прочитан: публикации источника теряют подтверждение, счётчики и сведения не
     * меняются; обход сохраняется в той же транзакции.
     *
     * @param run обход {@code FAILED}, ещё не сохранённый
     */
    @Transactional
    public void recordUnavailable(CrawlRun run) {
        Instant now = clock.instant();
        Set<Long> touched = new HashSet<>();
        for (JobPosting posting : postings.findBySourceId(run.getSource().getId())) {
            posting.markUnconfirmed();
            touched.add(posting.getVacancy().getId());
        }
        refreshStates(touched, now);
        crawlLog.finish(run, now);
    }

    private void refreshStates(Set<Long> vacancyIds, Instant now) {
        if (vacancyIds.isEmpty()) {
            return;
        }
        postings.findByVacancyIdIn(vacancyIds).stream()
                .collect(Collectors.groupingBy(JobPosting::getVacancy))
                .forEach((vacancy, vacancyPostings) -> vacancy.refreshState(vacancyPostings, now));
    }
}
