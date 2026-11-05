package com.roleorienta.worker.occupation;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Примеры с известным кодом для распознавания по похожему названию ({@link SimilarTitleClassifier}; стенограмма §263):
 * новым примерам (названия вакансий портала с кодом профессии, названия профессий ESCO) — векторы модели, порциями
 * ({@code limits.examples-per-task}). Первый раз примеров около 50 тыс. — несколько заданий; новые названия портала
 * с кодом добавляются каждое задание.
 */
@Component
public class TitleExamples {

    private static final Logger LOG = LoggerFactory.getLogger(TitleExamples.class);

    private final TitleClassificationRepository repository;
    private final OllamaEmbedder embedder;
    private final TitleClassificationProperties properties;

    /**
     * @param repository примеры
     * @param embedder   векторы
     * @param properties модель, порция
     */
    public TitleExamples(TitleClassificationRepository repository, OllamaEmbedder embedder,
            TitleClassificationProperties properties) {
        this.repository = repository;
        this.embedder = embedder;
        this.properties = properties;
    }

    /**
     * Добавляет векторы следующей порции примеров.
     *
     * @return {@code true} — все примеры с векторами (порция не заполнена); {@code false} — остались ещё
     * @throws TitleClassifier.ClassifierUnavailable модель векторов недоступна — ничего из порции не записано
     */
    public boolean refresh() {
        int limit = properties.limits().examplesPerTask();
        List<TitleClassificationRepository.Example> pending = repository.pendingExamples(properties.model(), limit);
        if (pending.isEmpty()) {
            return true;
        }
        float[][] vectors = embedder.embed(pending.stream().map(TitleClassificationRepository.Example::text).toList());
        repository.saveExamples(pending, vectors, properties.model());
        LOG.info("Title examples: {} added", pending.size());
        return pending.size() < limit;
    }
}
