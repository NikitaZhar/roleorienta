package com.roleorienta.worker.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code RUN_PASS}: порция одной версии условий по проходу (технический документ §7).
 * Параметры: {@code {"passRunId": N, "conditionId": M}}. Повтор задания порцию не удваивает
 * ({@link DeliveryRepository#deliverPortion}).
 */
@Component
public class RunPassHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "RUN_PASS";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(RunPassHandler.class);

    private final DeliveryRepository repository;
    private final DeliveryProperties properties;
    private final Clock clock;

    /**
     * @param repository накопленные списки
     * @param properties срок без подтверждения
     * @param clock      часы
     */
    public RunPassHandler(DeliveryRepository repository, DeliveryProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param passRunId   проход
     * @param conditionId версия условий
     * @return ключ задания
     */
    public static String taskKey(long passRunId, long conditionId) {
        return "pass:" + passRunId + ":" + conditionId;
    }

    /**
     * @param passRunId   проход
     * @param conditionId версия условий
     * @return параметры задания в JSON
     */
    public static String payload(long passRunId, long conditionId) {
        return "{\"passRunId\":" + passRunId + ",\"conditionId\":" + conditionId + "}";
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = payload(task);
        long passRunId = payload.path("passRunId").asLong();
        long conditionId = payload.path("conditionId").asLong();
        Instant now = clock.instant();
        int delivered = repository.deliverPortion(conditionId, passRunId, now, now.minus(properties.hideAfter()));
        LOG.info("Pass {} condition {}: delivered {}", passRunId, conditionId, delivered);
        return new TaskOutcome.Done();
    }

    private static JsonNode payload(TaskRecord task) {
        try {
            return JSON.readTree(task.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed RUN_PASS payload: " + task.payload(), exception);
        }
    }
}
