package com.roleorienta.worker.adapters;

import java.util.function.Predicate;

/**
 * Рынок, которым ограничивается сбор публикаций источника (§62): «какие страны» и «какие
 * локации» считаются целевыми. Адаптер, умеющий фильтровать ленту на стороне провайдера
 * (Workday — {@code appliedFacets}), просит у источника только публикации рынка; адаптер,
 * который этого не умеет, область игнорирует и отдаёт всё (поведение по умолчанию).
 *
 * @param isMarketCountry  название страны (как её отдаёт провайдер, по-английски) — на рынке?
 * @param isMarketLocation название локации (офис/город, как его называет работодатель) —
 *                         однозначно на рынке?
 */
public record MarketScope(Predicate<String> isMarketCountry, Predicate<String> isMarketLocation) {

    /** Без ограничения рынком: провайдер отдаёт все публикации источника. */
    public static final MarketScope ALL = new MarketScope(c -> true, l -> true);

    /** Область задана (не {@link #ALL}). */
    public boolean restricted() {
        return this != ALL;
    }
}
