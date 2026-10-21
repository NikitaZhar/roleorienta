package com.roleorienta.worker.digest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

/**
 * Задание {@code SEND_DIGEST}: сводка новых вакансий одному пользователю за день (стенограмма §164). Параметры:
 * {@code {"userId": N, "day": "2026-10-21"}}; ключ — пользователь и день, поэтому второго письма за день не бывает.
 * Почтовый сервер не принял письмо — повтор позже (временный отказ); почта не настроена — задание не выполнено.
 */
@Component
public class SendDigestHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "SEND_DIGEST";

    /** Повтор после отказа почтового сервера — не раньше. */
    private static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DigestService digests;

    /**
     * @param digests сводка пользователю
     */
    public SendDigestHandler(DigestService digests) {
        this.digests = digests;
    }

    /**
     * @param userId пользователь
     * @param day    день по его часам
     * @return ключ задания
     */
    public static String taskKey(long userId, LocalDate day) {
        return "digest:" + userId + ":" + day;
    }

    /**
     * @param userId пользователь
     * @param day    день по его часам
     * @return параметры задания в JSON
     */
    public static String payload(long userId, LocalDate day) {
        return "{\"userId\":" + userId + ",\"day\":\"" + day + "\"}";
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = read(task);
        try {
            digests.send(payload.path("userId").asLong(), LocalDate.parse(payload.path("day").asText()));
            return new TaskOutcome.Done();
        } catch (MailException failure) {
            return new TaskOutcome.Retry("Mail server refused the digest: " + failure.getMessage(), RETRY_AFTER);
        } catch (IllegalStateException failure) {
            return new TaskOutcome.Failed(failure.getMessage());
        }
    }

    private static JsonNode read(TaskRecord task) {
        try {
            return JSON.readTree(task.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed SEND_DIGEST payload: " + task.payload(), exception);
        }
    }
}
