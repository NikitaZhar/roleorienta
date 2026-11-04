package com.roleorienta.worker.occupation;

import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code TITLE_CLASSIFY} (стенограмма §262): названия вакансий без позиции — языковой модели, пачками
 * ({@link TitleClassifier}); ответы — в {@code title_classification}, распознанная группа — сразу публикациям
 * ({@link TitleClassificationRepository#save}). В каждой пачке несколько названий вакансий портала с кодом профессии —
 * для меры точности (их ответ публикациям не ставится: у них есть код портала).
 *
 * <p>Модель (Ollama) не запущена или не ответила — повтор задания позже; ответы уже полученных пачек сохранены.
 * Названия без ответа спросятся в следующем задании.</p>
 */
@Component
public class TitleClassificationHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "TITLE_CLASSIFY";

    /** Повтор после отказа сервиса — не раньше. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(15);

    private static final Logger LOG = LoggerFactory.getLogger(TitleClassificationHandler.class);

    private final TitleClassificationRepository repository;
    private final TitleClassifier classifier;
    private final TitleClassificationProperties properties;

    /**
     * @param repository названия и ответы
     * @param classifier распознавание
     * @param properties модель, пачки
     */
    public TitleClassificationHandler(TitleClassificationRepository repository, TitleClassifier classifier,
            TitleClassificationProperties properties) {
        this.repository = repository;
        this.classifier = classifier;
        this.properties = properties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        TitleClassificationProperties.Limits limits = properties.limits();
        List<TitleClassifier.Group> groups = repository.groups();
        int asked = 0;
        int recognized = 0;
        for (int batch = 0; batch < limits.batchesPerTask(); batch++) {
            List<String> titles = new ArrayList<>(repository.unrecognized(limits.batchSize() - limits.checkPerBatch()));
            if (titles.isEmpty()) {
                break;
            }
            titles.addAll(repository.coded(limits.checkPerBatch()));
            Map<String, String> answers;
            try {
                answers = classifier.classify(titles, groups);
            } catch (TitleClassifier.ClassifierUnavailable unavailable) {
                LOG.warn("Title classification stopped after {} titles: {}", asked, unavailable.getMessage());
                return new TaskOutcome.Retry(unavailable.getMessage(), RETRY_AFTER);
            }
            recognized += repository.save(answers, properties.model());
            asked += titles.size();
        }
        LOG.info("Title classification: {} titles asked, {} postings recognized", asked, recognized);
        return new TaskOutcome.Done();
    }
}
