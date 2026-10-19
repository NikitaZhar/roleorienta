package com.roleorienta.worker.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code COMPUTE_METRICS} (технический документ §7, §11; §131): пересчитать показатели ядра за день.
 * Параметры: {@code {"day": "2026-10-19"}}; границы дня — в часовом поясе {@link MetricsProperties#zone()}.
 */
@Component
public class ComputeMetricsHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "COMPUTE_METRICS";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(ComputeMetricsHandler.class);

    private final MetricsRepository repository;
    private final MetricsProperties properties;

    /**
     * @param repository показатели ядра
     * @param properties часовой пояс суток
     */
    public ComputeMetricsHandler(MetricsRepository repository, MetricsProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Параметры задания расчёта показателей.
     *
     * @param day день
     * @return JSON параметров
     */
    public static String payload(LocalDate day) {
        return "{\"day\":\"" + day + "\"}";
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        LocalDate day = day(task);
        Instant start = day.atStartOfDay(properties.zone()).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(properties.zone()).toInstant();
        repository.replaceDay(day, start, end);
        LOG.info("Core metrics computed for {}", day);
        return new TaskOutcome.Done();
    }

    private static LocalDate day(TaskRecord task) {
        try {
            return LocalDate.parse(JSON.readTree(task.payload()).path("day").asText());
        } catch (JsonProcessingException | DateTimeParseException exception) {
            throw new IllegalArgumentException("Malformed COMPUTE_METRICS payload: " + task.payload(), exception);
        }
    }
}
