package com.roleorienta.worker.adapters;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Реестр адаптеров источников: по коду провайдера выдаёт нужный адаптер.
 *
 * <p>Spring внедряет сюда список всех бинов {@link SourceAdapter} (внедрение
 * коллекции бинов — стандартный механизм DI: контейнер собирает все реализации
 * интерфейса). Реестр строит из них отображение «код провайдера → адаптер», что
 * позволяет добавлять новые системы найма отдельными бинами, не меняя обработчик
 * (принцип открытости/закрытости, §3.2 боевого контракта).</p>
 */
@Component
public class SourceAdapterRegistry {

    private final Map<String, SourceAdapter> byProviderCode;

    /**
     * @param adapters все зарегистрированные адаптеры источников
     * @throws IllegalStateException если два адаптера объявили один и тот же код провайдера
     */
    public SourceAdapterRegistry(List<SourceAdapter> adapters) {
        this.byProviderCode = adapters.stream().collect(Collectors.toMap(
                SourceAdapter::providerCode,
                Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException(
                            "Два адаптера для одного кода провайдера: " + first.providerCode());
                }));
    }

    /**
     * Возвращает адаптер для кода провайдера.
     *
     * @param providerCode код провайдера ({@code Provider.code})
     * @return адаптер этого провайдера
     * @throws IllegalArgumentException если адаптер для такого кода не зарегистрирован
     */
    public SourceAdapter forProviderCode(String providerCode) {
        SourceAdapter adapter = byProviderCode.get(providerCode);
        if (adapter == null) {
            throw new IllegalArgumentException("Нет адаптера для провайдера: " + providerCode);
        }
        return adapter;
    }
}
