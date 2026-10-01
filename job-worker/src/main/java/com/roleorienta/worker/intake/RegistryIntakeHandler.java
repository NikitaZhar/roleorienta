package com.roleorienta.worker.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Задание {@code REGISTRY_INTAKE}: одна партия общего сбора страны из её реестра ({@link CountryRegistry};
 * технический документ §5.1, подэтап 1.6). После партии ставится партия следующей в очереди страны
 * ({@link CollectionPlanner}) — партии стран чередуются.
 *
 * <p>Реестр временно не читается — повтор задания; место обработки сдвинуто другим заданием — задание
 * завершается без изменений.</p>
 */
@Component
public class RegistryIntakeHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "REGISTRY_INTAKE";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(RegistryIntakeHandler.class);

    private final CollectionPlanner planner;

    /**
     * @param planner очередь стран и их реестры
     */
    public RegistryIntakeHandler(CollectionPlanner planner) {
        this.planner = planner;
    }

    /**
     * @param country страна
     * @param batch   номер партии страны
     * @return ключ задания: повторная постановка той же партии ничего не добавляет
     */
    public static String taskKey(String country, long batch) {
        return "registry-intake:" + country + ":b" + batch;
    }

    /**
     * @param country страна
     * @return параметры задания
     */
    public static String payload(String country) {
        return JSON.createObjectNode().put("country", country).toString();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        String country = country(task);
        CountryRegistry registry = planner.registry(country);
        if (registry == null) {
            return new TaskOutcome.Failed("No registry for country " + country);
        }
        try {
            boolean hasMore = registry.intakeBatch();
            planner.batchDone(country, hasMore);
            return new TaskOutcome.Done();
        } catch (OptimisticLockingFailureException concurrent) {
            LOG.info("Registry intake {} is advanced by another task", country);
            return new TaskOutcome.Done();
        } catch (SdkException | IOException exception) {
            return new TaskOutcome.Retry("Registry " + country + " not read: " + exception.getMessage(),
                    Duration.ZERO);
        }
    }

    private static String country(TaskRecord task) {
        try {
            return JSON.readTree(task.payload()).path("country").asText();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed REGISTRY_INTAKE payload: " + task.payload(), exception);
        }
    }
}
