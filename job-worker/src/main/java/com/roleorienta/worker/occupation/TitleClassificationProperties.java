package com.roleorienta.worker.occupation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки распознавания позиции по похожему названию ({@code app.title-classification.*}; стенограмма §262, §263).
 * Модель векторов работает на компьютере, где запущен job-worker (Ollama): бесплатно, без внешних платных сервисов
 * (принцип проекта — только бесплатные источники, технический документ).
 *
 * @param enabled такт ставит задания распознавания ({@code TITLE_CLASSIFICATION_ENABLED})
 * @param model   модель векторов Ollama ({@code TITLE_CLASSIFICATION_MODEL}); векторы другой модели не сравниваются
 * @param api     адрес Ollama, срок ответа, размер запроса
 * @param limits  объём задания и правило ответа
 */
@ConfigurationProperties("app.title-classification")
public record TitleClassificationProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("bge-m3") String model, @DefaultValue Api api, @DefaultValue Limits limits) {

    /**
     * @param endpoint  адрес векторов Ollama ({@code OLLAMA_EMBED_URL})
     * @param timeout   срок ответа на один запрос
     * @param batchSize текстов в одном запросе
     */
    public record Api(@DefaultValue("http://localhost:11434/api/embed") String endpoint,
            @DefaultValue("120s") Duration timeout, @DefaultValue("64") int batchSize) {
    }

    /**
     * @param titlesPerTask   названий без позиции за задание (задание — раз в час)
     * @param examplesPerTask новых примеров с известным кодом за задание: первый раз их около 50 тыс. (≈ 12 мин на
     *                        Apple M5) — частями, чтобы задание укладывалось в аренду
     * @param neighbours      сколько ближайших примеров голосуют за код
     * @param minSimilarity   сходство с ближайшим примером не ниже — иначе «не понять» (замер §263: «Find out more»,
     *                        «Apply Now» — 0,5–0,7; настоящие названия — 0,8 и выше)
     */
    public record Limits(@DefaultValue("5000") int titlesPerTask, @DefaultValue("20000") int examplesPerTask,
            @DefaultValue("5") int neighbours, @DefaultValue("0.8") double minSimilarity) {
    }
}
