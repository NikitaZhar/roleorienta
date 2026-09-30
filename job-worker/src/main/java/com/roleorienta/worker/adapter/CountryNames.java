package com.roleorienta.worker.adapter;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Названия страны, которыми системы найма подписывают места: краткое английское («Slovakia») и
 * официальное («Slovak Republic»; SmartRecruiters пишет «Slovakia (Slovak Republic)»).
 */
public final class CountryNames {

    private static final Map<String, String> OFFICIAL = Map.of("SK", "Slovak Republic", "CZ", "Czech Republic");

    private CountryNames() {
    }

    /**
     * @param name    название страны у провайдера
     * @param country страна (ISO 3166-1 alpha-2)
     * @return название — это страна (без учёта регистра)
     */
    public static boolean isName(String name, String country) {
        return names(country).stream().anyMatch(name::equalsIgnoreCase);
    }

    /**
     * @param text    подпись места, например «Košice, Slovakia (Slovak Republic)»
     * @param country страна (ISO 3166-1 alpha-2)
     * @return в подписи есть название страны
     */
    public static boolean mentions(String text, String country) {
        String lower = text.toLowerCase(Locale.ROOT);
        return names(country).stream().anyMatch(name -> lower.contains(name.toLowerCase(Locale.ROOT)));
    }

    private static List<String> names(String country) {
        String english = Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
        String official = OFFICIAL.get(country);
        return official == null ? List.of(english) : List.of(english, official);
    }
}
