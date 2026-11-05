package com.roleorienta.worker.occupation;

import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code TITLE_CLASSIFY} (стенограмма §262, §263): сначала векторы новых примеров с известным кодом
 * ({@link TitleExamples}); пока примеры не готовы целиком, названия не распознаются (часть примеров дала бы худшие
 * ответы, а ответ на название не пересматривается). Затем названия вакансий без позиции ({@code limits.titles-per-task},
 * сначала общие для большего числа вакансий) — {@link TitleClassifier}; ответы — в {@code title_classification},
 * распознанная группа — сразу публикациям ({@link TitleClassificationRepository#save}).
 *
 * <p>Модель векторов (Ollama) не запущена или не ответила — повтор задания позже; ничего из незаконченной порции не
 * записано.</p>
 */
@Component
public class TitleClassificationHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "TITLE_CLASSIFY";

    /** Повтор после отказа сервиса — не раньше. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(15);

    private static final Logger LOG = LoggerFactory.getLogger(TitleClassificationHandler.class);

    private final TitleClassificationRepository repository;
    private final TitleExamples examples;
    private final TitleClassifier classifier;
    private final TitleClassificationProperties properties;

    /**
     * @param repository названия и ответы
     * @param examples   примеры с известным кодом
     * @param classifier распознавание
     * @param properties способ, объём задания
     */
    public TitleClassificationHandler(TitleClassificationRepository repository, TitleExamples examples,
            TitleClassifier classifier, TitleClassificationProperties properties) {
        this.repository = repository;
        this.examples = examples;
        this.classifier = classifier;
        this.properties = properties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        try {
            if (!examples.refresh()) {
                LOG.info("Title classification waits: examples are not ready yet");
                return new TaskOutcome.Done();
            }
            List<String> titles = repository.unrecognized(properties.limits().titlesPerTask());
            Map<String, String> answers = classifier.classify(titles);
            int postings = repository.save(answers, properties.model());
            long recognized = answers.values().stream().filter(code -> !code.isEmpty()).count();
            LOG.info("Title classification: {} titles asked, {} recognized, {} postings recognized", titles.size(),
                    recognized, postings);
            return new TaskOutcome.Done();
        } catch (TitleClassifier.ClassifierUnavailable unavailable) {
            LOG.warn("Title classification stopped: {}", unavailable.getMessage());
            return new TaskOutcome.Retry(unavailable.getMessage(), RETRY_AFTER);
        }
    }
}
