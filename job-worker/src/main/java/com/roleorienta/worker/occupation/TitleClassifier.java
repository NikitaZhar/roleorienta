package com.roleorienta.worker.occupation;

import java.util.List;
import java.util.Map;

/**
 * Распознавание позиции вакансии по названию (технический документ §15, MVP п. 5б, шаг 1; стенограмма §262, §263):
 * название → группа ISCO-08 уровня 4. Общий интерфейс, чтобы способ (похожее название, языковая модель, правила одной
 * страны) менялся без правки остального приложения.
 */
public interface TitleClassifier {

    /**
     * @param titles названия вакансий
     * @return название → код группы; названия, которые не распознаны (не вакансия, профессию не определить), — с
     *         пустым значением
     * @throws ClassifierUnavailable сервис распознавания не ответил или не готов — спросить позже
     */
    Map<String, String> classify(List<String> titles);

    /**
     * Сервис распознавания временно недоступен (не запущен, не ответил в срок, отказ).
     */
    class ClassifierUnavailable extends RuntimeException {

        /**
         * @param message причина
         */
        public ClassifierUnavailable(String message) {
            super(message);
        }
    }
}
