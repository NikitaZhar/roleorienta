package com.roleorienta.worker.intake;

import com.roleorienta.worker.task.TaskService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Чередование партий между странами сбора (бизнес-описание §4.1, сценарий 4): следующей ставится
 * партия страны, чья последняя партия старше всех; у каждой страны своё место обработки. Страна без
 * адаптера реестра в очередь не попадает.
 */
@Service
public class CollectionPlanner {

    private final CollectionRepository repository;
    private final Map<String, CountryRegistry> registries;
    private final TaskService taskService;

    /**
     * @param repository  страны сбора
     * @param registries  адаптеры реестров стран
     * @param taskService постановка заданий
     */
    public CollectionPlanner(CollectionRepository repository, List<CountryRegistry> registries,
            TaskService taskService) {
        this.repository = repository;
        this.registries = registries.stream().collect(Collectors.toMap(CountryRegistry::country,
                Function.identity()));
        this.taskService = taskService;
    }

    /**
     * @param country страна
     * @return адаптер реестра страны; {@code null} — нет
     */
    CountryRegistry registry(String country) {
        return registries.get(country);
    }

    /**
     * Обновляет страны сбора по условиям пользователей, отмечает завершённые первичные обходы и ставит
     * партию первой в очереди страны.
     */
    public void refreshAndEnqueue() {
        repository.refreshCountries();
        repository.markFirstPassDone();
        enqueueNext();
    }

    /**
     * Партия страны прочитана — ставится партия следующей в очереди страны.
     *
     * @param country страна
     * @param hasMore у страны записи ещё есть
     */
    void batchDone(String country, boolean hasMore) {
        repository.recordBatch(country, hasMore);
        enqueueNext();
    }

    private void enqueueNext() {
        repository.queue().stream().filter(turn -> registries.containsKey(turn.country())).findFirst()
                .ifPresent(turn -> taskService.enqueue(RegistryIntakeHandler.TYPE,
                        RegistryIntakeHandler.taskKey(turn.country(), turn.batches()),
                        RegistryIntakeHandler.payload(turn.country())));
    }
}
