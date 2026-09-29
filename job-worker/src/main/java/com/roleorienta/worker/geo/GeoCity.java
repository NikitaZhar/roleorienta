package com.roleorienta.worker.geo;

/**
 * Город GeoNames.
 *
 * @param name       нормализованное название
 * @param country    страна (ISO 3166-1 alpha-2)
 * @param population население
 */
public record GeoCity(String name, String country, long population) {
}
