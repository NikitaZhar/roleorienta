package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.adapter.stateportal.ProfesiaPosition;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Duration;
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
 */
@Component
public class ProfesiaPositionsHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PROFESIA_POSITIONS";

    /** Страниц объявлений за задание. */
    static final int ADS_PER_TASK = 300;

    /** Срок задания (аренда задания — дольше). */
    static final int TIME_BUDGET_MINUTES = 8;

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
        Map<Long, Map<String, List<ProfesiaPosition>>> bySource = new HashMap<>();
        int read = 0;
        boolean unavailable = false;
        for (PortalListRepository.UncheckedAd ad : repository.uncheckedProfesiaAds(ADS_PER_TASK)) {
            if (System.nanoTime() - deadline > 0) {
                break;
            }
            Optional<List<ProfesiaPosition>> positions = pages.positions(ad.url());
            if (positions.isEmpty()) {
                unavailable = true;
                break;
            }
            bySource.computeIfAbsent(ad.sourceId(), source -> new HashMap<>()).put(ad.externalId(), positions.get());
            read++;
        }
        bySource.forEach(repository::recordProfesiaPositions);
        LOG.info("Profesia positions: {} offer pages read{}", read, unavailable ? ", profesia.sk unavailable" : "");
        return unavailable ? new TaskOutcome.Retry("profesia.sk unavailable", RETRY_AFTER) : new TaskOutcome.Done();
    }
}
