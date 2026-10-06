package com.roleorienta.worker.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.intake.CompanySizeRepository.DueCompany;
import com.roleorienta.worker.intake.RuzClient.RuzSize;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code COMPANY_SIZE} — число сотрудников компаний Словакии из RÚZ ({@link RuzClient}; технический
 * документ §5.1): по {@link CompanySizeProperties#companiesPerTask()} компаний за задание записывается нижняя граница
 * категории размера; взято полное число — следующее задание цепочки. Первый проход по всем компаниям — дни (два
 * запроса на компанию, пауза на хост), дальше — только компании, спрошенные давнее срока.
 *
 * <p>RÚZ не ответил по компании — она остаётся к запросу следующим заданием; не ответил ни разу за задание —
 * повтор задания позже. Нет разрешения в {@code source_permission} — задание ничего не делает.</p>
 */
@Component
public class CompanySizeHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "COMPANY_SIZE";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(CompanySizeHandler.class);

    private final RuzClient client;
    private final CompanySizeRepository repository;
    private final TaskService taskService;
    private final CompanySizeProperties properties;

    /**
     * @param client      категория из RÚZ
     * @param repository  компании к запросу и запись
     * @param taskService постановка следующего задания цепочки
     * @param properties  компаний за задание, срок повтора
     */
    public CompanySizeHandler(RuzClient client, CompanySizeRepository repository, TaskService taskService,
            CompanySizeProperties properties) {
        this.client = client;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * Ставит первое задание цепочки.
     *
     * @param period часть ключа: день (шаг ставится раз в сутки)
     * @return поставлено ли (повтор в тот же день ничего не добавляет)
     */
    public boolean enqueue(String period) {
        return taskService.enqueue(TYPE, key(period, 1), payload(period, 1));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        String period = payload.path("period").asText();
        int round = payload.path("round").asInt(1);
        if (!repository.permitted()) {
            LOG.info("RÚZ is not permitted in source_permission, company size step skipped");
            return new TaskOutcome.Done();
        }
        List<DueCompany> due = repository.dueCompanies(properties.companiesPerTask(), properties.recheckAfter());
        int answered = 0;
        for (DueCompany company : due) {
            Optional<RuzSize> size = client.size(company.registrationNumber());
            if (size.isPresent()) {
                repository.record(company.id(), size.get().employeesMin());
                answered++;
            }
        }
        LOG.info("Company size from RÚZ: {} of {} companies answered", answered, due.size());
        if (answered == 0 && !due.isEmpty()) {
            return new TaskOutcome.Retry("RÚZ not answering", Duration.ZERO);
        }
        if (due.size() == properties.companiesPerTask()) {
            taskService.enqueue(TYPE, key(period, round + 1), payload(period, round + 1));
        }
        return new TaskOutcome.Done();
    }

    private static String key(String period, int round) {
        return "company-size:" + period + ":" + round;
    }

    private static String payload(String period, int round) {
        return JSON.createObjectNode().put("period", period).put("round", round).toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed COMPANY_SIZE payload: " + payload, exception);
        }
    }
}
