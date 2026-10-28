package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.stateportal.ProfesiaOfferRepository.EmployerOffers;
import com.roleorienta.worker.stateportal.ProfesiaOfferRepository.ListedOffer;
import com.roleorienta.worker.stateportal.ProfesiaPages.OfferFound;
import com.roleorienta.worker.stateportal.ProfesiaPages.OfferGone;
import com.roleorienta.worker.stateportal.ProfesiaPages.OfferUnavailable;
import com.roleorienta.worker.stateportal.ProfesiaPages.OfferUnparsed;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Задание {@code PROFESIA_OFFERS} — объявления profesia.sk, которых нет на государственном портале (решение владельца
 * 2026-10-28, §219): по замеру около 14 % объявлений profesia.sk через портал не приходят. Цепочку ставит чтение карты
 * сайта ({@link ProfesiaListHandler}, раз в сутки), объявления карты сайта уже в {@code profesia_offer}. Задание — круг
 * в час (квота, §101):
 *
 * <ol>
 *   <li>страницы до {@code pages-per-task} объявлений, которых нет на портале и чья страница ещё не прочитана, новые
 *       первыми ({@link ProfesiaPages#offer}): сведения записываются, нет страницы — больше не запрашивается,
 *       страница без названия — снова через 7 дней; временный отказ profesia.sk — запись прочитанного и повтор
 *       задания;</li>
 *   <li>запись: в первом круге — все работодатели (в том числе те, у кого объявления ушли: из карты сайта или на
 *       портал), дальше — работодатели прочитанных страниц. Работодатель — источник провайдера {@code profesia}
 *       (доска — работодатель в адресе, без компании — V86), его объявления — полное чтение ({@link PostingRecorder}):
 *       ушедшему засчитывается отсутствие, закрытие — после N полных чтений подряд.</li>
 * </ol>
 *
 * <p>Общий список портала устарел (больше 2 суток) — задание ничего не делает (аудит §222): «нет на портале» тогда
 * ничего не значит. Запись работодателей сроком задания не ограничена: аренда продлевается до {@code max-run}.</p>
 *
 * <p>Приложение показывает о таких вакансиях только краткие сведения и ссылку на объявление; текст — только для
 * сопоставления (основание — {@code source_permission}, V86).</p>
 */
@Component
public class ProfesiaOffersHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PROFESIA_OFFERS";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ProfesiaOffersHandler.class);

    private final ProfesiaOfferRepository repository;
    private final ProfesiaPages pages;
    private final PostingRecorder recorder;
    private final SourceRepository sources;
    private final ProfesiaOffersProperties properties;

    /**
     * @param repository объявления и их источники
     * @param pages      страницы объявлений profesia.sk
     * @param recorder   запись публикаций и обхода
     * @param sources    источники
     * @param properties размер задания и включение
     */
    public ProfesiaOffersHandler(ProfesiaOfferRepository repository, ProfesiaPages pages, PostingRecorder recorder,
            SourceRepository sources, ProfesiaOffersProperties properties) {
        this.repository = repository;
        this.pages = pages;
        this.recorder = recorder;
        this.sources = sources;
        this.properties = properties;
    }

    /**
     * @param day   день карты сайта
     * @param round круг, с 1
     * @return ключ задания
     */
    static String key(String day, int round) {
        return "profesia-offers:" + day + ":" + round;
    }

    /**
     * @param day   день карты сайта
     * @param round круг, с 1
     * @return параметры задания в JSON
     */
    static String payload(String day, int round) {
        return JSON.createObjectNode().put("day", day).put("round", round).toString();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        if (!properties.enabled()) {
            return new TaskOutcome.Done();
        }
        if (!repository.portalFresh()) {
            LOG.warn("Profesia offers: state portal list is stale, offers are not added or closed");
            return new TaskOutcome.Done();
        }
        JsonNode payload = parse(task.payload());
        String day = payload.path("day").asText();
        int round = payload.path("round").asInt(1);
        long deadline = System.nanoTime() + properties.timeBudget().toNanos();
        List<ListedOffer> due = repository.toRead(properties.pagesPerTask());
        Set<String> touched = new LinkedHashSet<>();
        int handled = 0;
        int gone = 0;
        int unparsed = 0;
        for (ListedOffer offer : due) {
            if (System.nanoTime() - deadline > 0) {
                break;
            }
            switch (pages.offer(offer.url())) {
                case OfferFound found -> {
                    repository.saveRead(offer.num(), found.offer());
                    touched.add(offer.employer());
                }
                case OfferGone missing -> {
                    repository.saveGone(offer.num());
                    gone++;
                }
                case OfferUnparsed blank -> {
                    repository.saveUnparsed(offer.num());
                    unparsed++;
                }
                case OfferUnavailable unavailable -> {
                    int recorded = record(touched, task.id());
                    LOG.warn("Profesia offers: profesia.sk unavailable after {} pages, {} employers recorded: {}",
                            handled, recorded, unavailable.failure().reason());
                    return new TaskOutcome.Retry("profesia.sk unavailable: " + unavailable.failure().reason(),
                            unavailable.failure().retryAfter());
                }
            }
            handled++;
        }
        int recorded = record(round == 1 ? repository.employersToRecord() : touched, task.id());
        LOG.info("Profesia offers round {}: {} pages read, {} gone, {} without title, {} employers recorded", round,
                handled, gone, unparsed, recorded);
        if (due.size() == properties.pagesPerTask() || handled < due.size()) {
            return new TaskOutcome.Continue(key(day, round + 1), payload(day, round + 1));
        }
        return new TaskOutcome.Done();
    }

    /**
     * @return сколько работодателей записано
     */
    private int record(Collection<String> employers, long taskId) {
        int recorded = 0;
        for (String employer : employers) {
            recorded += record(employer, taskId) ? 1 : 0;
        }
        return recorded;
    }

    /**
     * Объявления работодателя — полное чтение его источника; источника нет и объявлений нет — ничего.
     */
    private boolean record(String employer, long taskId) {
        EmployerOffers offers = repository.offersOf(employer);
        Optional<Source> found = repository.source(employer, offers.employerName(), !offers.postings().isEmpty())
                .flatMap(sources::findById);
        if (found.isEmpty() || found.get().isClosed()) {
            return false;
        }
        CrawlRun run = recorder.startRun(found.get(), taskId);
        run.read(offers.postings().size(), null);
        try {
            recorder.record(run, offers.postings(), Set.of(), Set.of(), offers.postedAt());
        } catch (DataIntegrityViolationException concurrent) {
            LOG.warn("Profesia offers: source {} not recorded: {}", found.get().getId(), concurrent.getMessage());
            return false;
        }
        return true;
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PROFESIA_OFFERS payload: " + payload, exception);
        }
    }
}
