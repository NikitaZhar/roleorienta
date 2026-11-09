package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.adapter.stateportal.ProfesiaPosition;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code PROFESIA_POSITIONS} (§267): позиции profesia.sk со страниц объявлений, у которых их ещё нет, —
 * накопленные объявления любого источника (общий список портала, добор с profesia.sk). Без него позиции накопленных
 * объявлений появлялись бы только при очередной записи источника портала (до 10 страниц за запись, полная запись — раз
 * в сутки) и никогда — у прочитанных до §267 объявлений добора. Новые объявления получают позиции и при обычной записи
 * и доборе. Не больше {@value #ADS_PER_TASK} страниц за задание (раз в час; пауза на хост и robots.txt соблюдает
 * {@code ExternalHttpClient}), сначала работодатели с большим числом таких объявлений; не дольше
 * {@value #TIME_BUDGET_MINUTES} мин. profesia.sk временно недоступна — прочитанное записано, задание повторяется.
 * Заодно — ссылка на вакансию у работодателя по кнопке отклика (§268), если её ещё нет: по ней объявление склеивается
 * с вакансией системы найма работодателя; объявления, позиции которых прочитаны раньше, читаются снова ради кнопки
 * (§271, аудит §270 замечание 4).
 *
 * <p>Страница без объявления (капча, баннер) «позиций нет» не записывает (аудит §270 замечание 6): одна такая
 * страница откладывается на {@code PortalListRepository.DEFERRED_PAGE_DAYS} дн., так же — страница, у которой
 * переадресация кнопки не получена; {@value #UNPARSED_IN_ROW} подряд — капча: прочитанное записано, задание
 * повторяется через 15 мин, эти объявления не откладываются.</p>
 */
@Component
public class ProfesiaPositionsHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PROFESIA_POSITIONS";

    /** Страниц объявлений за задание. */
    static final int ADS_PER_TASK = 300;

    /** Срок задания (аренда задания — дольше). */
    static final int TIME_BUDGET_MINUTES = 8;

    /** Страниц без объявления подряд — profesia.sk отдаёт капчу или баннер: задание прерывается и повторяется. */
    static final int UNPARSED_IN_ROW = 3;

    private static final Duration RETRY_AFTER = Duration.ofMinutes(15);
    private static final Logger LOG = LoggerFactory.getLogger(ProfesiaPositionsHandler.class);

    private final PortalListRepository repository;
    private final ProfesiaPages pages;

    /**
     * @param repository объявления без позиций, запись позиций
     * @param pages      страницы объявлений profesia.sk
     */
    public ProfesiaPositionsHandler(PortalListRepository repository, ProfesiaPages pages) {
        this.repository = repository;
        this.pages = pages;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        long deadline = System.nanoTime() + Duration.ofMinutes(TIME_BUDGET_MINUTES).toNanos();
        Read read = new Read();
        List<PortalListRepository.UncheckedAd> unparsed = new ArrayList<>();
        String stop = null;
        for (PortalListRepository.UncheckedAd ad : repository.uncheckedProfesiaAds(ADS_PER_TASK)) {
            if (System.nanoTime() - deadline > 0) {
                break;
            }
            Optional<ProfesiaPages.AdFacts> facts = pages.positions(ad.url());
            if (facts.isEmpty()) {
                stop = "profesia.sk unavailable";
                break;
            }
            if (!facts.get().offerPage()) {
                unparsed.add(ad);
                if (unparsed.size() >= UNPARSED_IN_ROW) {
                    // подряд страницы без объявления — капча или сбой площадки, а не эти объявления: не откладывать
                    unparsed.clear();
                    stop = "profesia.sk returns pages without an offer";
                    break;
                }
                continue;
            }
            unparsed.forEach(read::defer);
            unparsed.clear();
            read.add(ad, facts.get());
        }
        unparsed.forEach(read::defer);
        read.positions.forEach(repository::recordProfesiaPositions);
        read.links.forEach(repository::recordEmployerLinks);
        read.deferred.forEach(repository::deferProfesiaPages);
        LOG.info("Profesia positions: {} offer pages read, {} deferred{}", read.pages,
                read.deferred.values().stream().mapToInt(List::size).sum(), stop == null ? "" : ", " + stop);
        return stop == null ? new TaskOutcome.Done() : new TaskOutcome.Retry(stop, RETRY_AFTER);
    }

    /** Прочитанное за задание — по источникам. */
    private static final class Read {

        private final Map<Long, Map<String, List<ProfesiaPosition>>> positions = new HashMap<>();
        private final Map<Long, Map<String, String>> links = new HashMap<>();
        private final Map<Long, List<String>> deferred = new HashMap<>();
        private int pages;

        /**
         * Страница объявления прочитана: позиции — всегда; ссылка — выясненная (и «нет ссылки»); не выяснена —
         * страница откладывается и прочитается снова ради кнопки.
         */
        void add(PortalListRepository.UncheckedAd ad, ProfesiaPages.AdFacts facts) {
            positions.computeIfAbsent(ad.sourceId(), source -> new HashMap<>()).put(ad.externalId(), facts.positions());
            if (facts.link() == null) {
                defer(ad);
            } else {
                links.computeIfAbsent(ad.sourceId(), source -> new HashMap<>()).put(ad.externalId(), facts.link().url());
            }
            pages++;
        }

        void defer(PortalListRepository.UncheckedAd ad) {
            deferred.computeIfAbsent(ad.sourceId(), source -> new ArrayList<>()).add(ad.externalId());
        }
    }
}
