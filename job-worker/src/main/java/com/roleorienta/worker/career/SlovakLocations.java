package com.roleorienta.worker.career;

import java.util.List;
import java.util.Locale;

/**
 * Место работы в Словакии по тексту места публикации: название страны или крупного города
 * (с диакритикой и без). Грубый признак для отбора досок; точная страна вакансии — задача отбора
 * (подэтап 1.4).
 */
final class SlovakLocations {

    private static final List<String> MARKERS = List.of("slovak", "slovensk", "bratislava", "košice", "kosice",
            "žilina", "zilina", "prešov", "presov", "banská bystrica", "banska bystrica", "trnava", "trenčín",
            "trencin", "nitra", "martin, sk", "poprad");

    private SlovakLocations() {
    }

    /**
     * @param location место публикации; {@code null} — не указано
     * @return {@code true} — похоже на Словакию
     */
    static boolean matches(String location) {
        if (location == null) {
            return false;
        }
        String lower = location.toLowerCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(lower::contains);
    }
}
