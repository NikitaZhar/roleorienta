package com.roleorienta.worker.occupation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки распознавания позиции локальной языковой моделью ({@code app.title-classification.*}; стенограмма §262).
 * Модель работает на компьютере, где запущен job-worker (Ollama): бесплатно, без внешних платных сервисов (принцип
 * проекта — только бесплатные источники, технический документ).
 *
 * @param enabled такт ставит задания распознавания ({@code TITLE_CLASSIFICATION_ENABLED})
 * @param model   модель Ollama ({@code TITLE_CLASSIFICATION_MODEL})
 * @param api     адрес Ollama, срок ответа, размер контекста
 * @param limits  пачки и проверка точности
 */
@ConfigurationProperties("app.title-classification")
public record TitleClassificationProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("qwen2.5:7b") String model, @DefaultValue Api api, @DefaultValue Limits limits) {

    /**
     * @param endpoint      адрес чата Ollama ({@code OLLAMA_URL})
     * @param timeout       срок ответа на одну пачку
     * @param contextTokens контекст модели в токенах: каталог групп (около 5 тыс.) и пачка названий должны поместиться
     *                      — у Ollama по умолчанию контекст меньше
     */
    public record Api(@DefaultValue("http://localhost:11434/api/chat") String endpoint,
            @DefaultValue("180s") Duration timeout, @DefaultValue("8192") int contextTokens) {
    }

    /**
     * @param batchSize      названий в одном запросе
     * @param batchesPerTask запросов за задание (задание — раз в час)
     * @param checkPerBatch  сколько названий пачки — из вакансий портала с кодом профессии: ответ модели сверяется с
     *                       кодом портала — мера точности (target/s262-model-check.sql)
     */
    public record Limits(@DefaultValue("20") int batchSize, @DefaultValue("60") int batchesPerTask,
            @DefaultValue("2") int checkPerBatch) {
    }
}
