package com.roleorienta.worker.occupation;

import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code TITLE_CLASSIFY} (стенограмма §262, §263): сначала векторы новых примеров с известным кодом
 * ({@link TitleExamples}); пока примеры не готовы целиком, названия не распознаются (часть примеров дала бы худшие
 * ответы, а ответ на название не пересматривается). Затем названия вакансий без позиции ({@code limits.titles-per-task},
 * сначала общие для большего числа вакансий) — {@link TitleClassifier}; ответы — в {@code title_classification},
 * распознанная группа — сразу публикациям ({@link TitleClassificationRepository#save}). Тем же вызовом — позиции
 * каталога profesia.sk без группы ISCO-08 (§267; «Predavač/predavačka», «Database Administrator»; не понятые — снова
 * через 7 дн., §271); владелец проверяет и исправляет ({@code mapped_by = 'OWNER'}).
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
            Map<String, String> positions = repository.unmappedProfesiaPositions();
            List<String> titles = repository.unrecognized(properties.limits().titlesPerTask());
            // одним вызовом: примеры (сотни МБ) загружаются один раз (аудит §270 замечание 12)
            Set<String> asked = new LinkedHashSet<>(positions.values());
            asked.addAll(titles);
            Map<String, String> answers = asked.isEmpty() ? Map.of() : classifier.classify(List.copyOf(asked));
            if (!positions.isEmpty()) {
                Map<String, String> codes = new HashMap<>();
                positions.forEach((slug, name) -> codes.put(slug, answers.getOrDefault(name, "")));
                repository.mapProfesiaPositions(codes, properties.model());
                LOG.info("Profesia positions mapped: {}, with a group: {}", codes.size(),
                        codes.values().stream().filter(code -> !code.isEmpty()).count());
            }
            Map<String, String> titleAnswers = new HashMap<>();
            titles.forEach(title -> titleAnswers.put(title, answers.getOrDefault(title, "")));
            int postings = repository.save(titleAnswers, properties.model());
            long recognized = titleAnswers.values().stream().filter(code -> !code.isEmpty()).count();
            LOG.info("Title classification: {} titles asked, {} recognized, {} postings recognized", titles.size(),
                    recognized, postings);
            return new TaskOutcome.Done();
        } catch (TitleClassifier.ClassifierUnavailable unavailable) {
            LOG.warn("Title classification stopped: {}", unavailable.getMessage());
            return new TaskOutcome.Retry(unavailable.getMessage(), RETRY_AFTER);
        }
    }
}
