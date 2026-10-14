package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.StatePortalProperties;
import com.roleorienta.worker.stateportal.ProfesiaPages.Found;
import com.roleorienta.worker.stateportal.ProfesiaPages.Lookup;
import com.roleorienta.worker.stateportal.ProfesiaPages.Unavailable;
import com.roleorienta.worker.stateportal.StatePortalRepository.ProfesiaEmployer;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Задание {@code PROFESIA_SITE} — шаг «страница компании на profesia.sk» (§82; путь владельца: портал → объявление
 * profesia.sk → страница компании на profesia.sk → кадровая страница компании). У работодателя портала с объявлением
 * profesia.sk открывается объявление, с него — страница компании и её разделы (до пяти, {@link ProfesiaPages#find});
 * первая ссылка на кадровую страницу или систему найма записывается сайтом компании со стартовой страницей — этой
 * ссылкой, иначе первая ссылка — сайтом компании (подтверждение {@code PROFESIA}: объявление привязано к IČO порталом).
 * Дальше их проверяет поиск кадровой страницы. profesia.sk — только путь к сайту: вакансии с неё не читаются.
 *
 * <p>За задание — до {@value #EMPLOYERS_PER_TASK} работодателей (до семи запросов на работодателя с паузой 1 с на
 * хост); после срока задания ({@code app.state-portal.profesia-task-time-budget}) новые работодатели не начинаются —
 * задание укладывается в аренду (аудит §84). Взято полное число или срок вышел — следующее задание в следующем часу
 * (квота в час — размер задания, §101). Работодатель
 * отмечается проверенным, когда ссылка найдена или её нет, и берётся снова через
 * {@link StatePortalProperties#recheckAfter()}; временный отказ profesia.sk — работодатель не отмечается, задание
 * повторяется позже; с третьего повтора такой работодатель отмечается без ссылки (аудит §84).</p>
 */
@Component
public class ProfesiaSiteHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PROFESIA_SITE";

    private static final int EMPLOYERS_PER_TASK = 20;
    /**
     * С этой попытки задания работодатель, на котором profesia.sk отвечает временным отказом, отмечается проверенным
     * без ссылки (аудит §84): постоянно «временно» сломанная страница одного работодателя не держит цепочку; при сбое
     * всей profesia.sk так отмечаются не больше одного работодателя на попытку.
     */
    private static final int SKIP_EMPLOYER_AFTER_ATTEMPTS = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ProfesiaSiteHandler.class);

    private final ProfesiaPages pages;
    private final StatePortalRepository repository;
    private final TaskService taskService;
    private final StatePortalProperties properties;
    private final Duration timeBudget;

    /**
     * @param pages       чтение profesia.sk: объявление → страница компании → ссылка
     * @param repository  работодатели портала и запись сайта
     * @param taskService постановка следующего задания цепочки
     * @param properties  срок повторной проверки
     * @param timeBudget  срок задания: после него новые работодатели не начинаются
     */
    public ProfesiaSiteHandler(ProfesiaPages pages, StatePortalRepository repository, TaskService taskService,
            StatePortalProperties properties,
            @Value("${app.state-portal.profesia-task-time-budget:3m}") Duration timeBudget) {
        this.pages = pages;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
        this.timeBudget = timeBudget;
    }

    /**
     * Ставит первое задание цепочки.
     *
     * @param day часть ключа: день
     * @return поставлено ли (цепочка идёт или повтор в тот же день — ничего не добавляется; стенограмма §95)
     */
    public boolean enqueue(String day) {
        return taskService.startChain(TYPE, key(day, 1), payload(day, 1));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        String day = payload.path("day").asText();
        int round = payload.path("round").asInt(1);
        int attempts = task.attempts();
        long deadline = System.nanoTime() + timeBudget.toNanos();
        List<ProfesiaEmployer> due = repository.employersForProfesia(EMPLOYERS_PER_TASK, properties.recheckAfter());
        int done = 0;
        int found = 0;
        for (ProfesiaEmployer employer : due) {
            if (System.nanoTime() - deadline > 0) {
                break;
            }
            Lookup lookup = pages.find(employer.adUrl());
            if (lookup instanceof Unavailable unavailable) {
                if (attempts >= SKIP_EMPLOYER_AFTER_ATTEMPTS) {
                    repository.recordProfesia(employer, null);
                }
                LOG.warn("Profesia company pages: profesia.sk unavailable after {} employers (attempt {}): {}", done,
                        attempts, unavailable.failure().reason());
                return new TaskOutcome.Retry("profesia.sk unavailable: " + unavailable.failure().reason(),
                        unavailable.failure().retryAfter());
            }
            repository.recordProfesia(employer, lookup instanceof Found site ? site.site() : null);
            done++;
            found += lookup instanceof Found ? 1 : 0;
        }
        LOG.info("Profesia company pages: {} employers, {} sites or career pages found", done, found);
        if (due.size() == EMPLOYERS_PER_TASK || done < due.size()) {
            taskService.enqueueNextHour(TYPE, key(day, round + 1), payload(day, round + 1));
        }
        return new TaskOutcome.Done();
    }

    private static String key(String day, int round) {
        return "profesia-site:" + day + ":" + round;
    }

    private static String payload(String day, int round) {
        return JSON.createObjectNode().put("day", day).put("round", round).toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PROFESIA_SITE payload: " + payload, exception);
        }
    }
}
