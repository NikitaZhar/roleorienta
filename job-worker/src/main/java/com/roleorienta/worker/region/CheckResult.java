package com.roleorienta.worker.region;

/**
 * Итог проверки пути «компания → сайт → карьерная страница» (§95).
 *
 * @param website подтверждённый сайт или {@code null}
 * @param career  карьерная страница или {@code null} (сайт не найден)
 * @param note    пояснение для человека
 */
public record CheckResult(String website, CareerPage career, String note) {
}
