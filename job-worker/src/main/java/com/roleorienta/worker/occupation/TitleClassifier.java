package com.roleorienta.worker.occupation;

import java.util.List;
import java.util.Map;

/**
 * Распознавание позиции вакансии по названию (технический документ §15, MVP п. 5б, шаг 1; стенограмма §262): название
 * → группа ISCO-08 уровня 4 из каталога. Общий интерфейс, чтобы способ (языковая модель, правила одной страны)
 * менялся без правки остального приложения.
 */
public interface TitleClassifier {

    /**
     * @param titles названия вакансий (одна пачка)
     * @param groups каталог: группы ISCO-08 уровня 4 с названиями
     * @return название → код группы; названия, которые не распознаны (не вакансия, профессию не определить), — с
     *         пустым значением; названия, ответа о которых нет (сбой), в результат не входят
     * @throws ClassifierUnavailable сервис распознавания не ответил — пачку спросить позже
     */
    Map<String, String> classify(List<String> titles, List<Group> groups);

    /**
     * Группа ISCO-08 каталога.
     *
     * @param code код уровня 4
     * @param name название (английское)
     */
    record Group(String code, String name) {
    }

    /**
     * Сервис распознавания временно недоступен (сеть, отказ, превышение частоты).
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
