package com.roleorienta.worker.coverage;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Бренд доски, отличный от работодателя (§86). У одного тенанта Workday бывают сайты других
 * брендов: {@code accenture/AvanadeCareers}, {@code capri/Michael_Kors}. Их вакансии на площадке
 * опубликованы под брендом, и поиск по имени компании («Accenture») их не находит — получалось
 * ложное «только с сайта».
 *
 * <p>Бренд берётся из имени сайта (часть адреса доски после {@code '/'}): имя делится на слова
 * по {@code _}, {@code -}, цифрам и смене регистра ({@code AvanadeCareers} → «Avanade Careers»),
 * общие слова сайтов ({@link #SITE_WORDS}: Careers, Jobs, External…) отбрасываются. Бренда нет,
 * если слов не осталось, если бренд короче {@link #MIN_BRAND_LENGTH} букв или если он совпадает
 * с именем компании или тенантом (одно содержит другое без учёта регистра и знаков:
 * {@code lilly/LLY}, {@code dxctechnology/DXCJobs}).</p>
 */
final class BoardBrand {

    /** Бренд короче (в буквах и цифрах) не ищется: «EU», «AT» дают случайную выдачу. */
    static final int MIN_BRAND_LENGTH = 3;

    /** Общие слова в именах сайтов карьерных досок — не бренд (сравнение в нижнем регистре). */
    static final Set<String> SITE_WORDS = Set.of(
            "careers", "career", "karriere", "jobs", "job", "external", "ext", "internal", "int",
            "site", "sites", "search", "global", "portal", "opportunities", "openings", "recruiting",
            "hiring", "join", "us", "en", "de", "at", "talent", "candidate", "candidates", "public",
            "experienced", "professionals", "apply", "home", "page", "workday");

    private static final String WORD_BOUNDARY =
            "[_\\-\\s.]+|(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})";

    private BoardBrand() {
    }

    /**
     * Бренд доски, если он отличается от работодателя.
     *
     * @param externalRef адрес доски у провайдера ({@code тенант/сайт}); без сайта бренда нет
     * @param companyName имя компании
     * @return бренд для поиска на площадке или пусто, если искать нужно по имени компании
     */
    static Optional<String> of(String externalRef, String companyName) {
        if (externalRef == null || externalRef.indexOf('/') < 0) {
            return Optional.empty();
        }
        String tenant = externalRef.substring(0, externalRef.indexOf('/'));
        String site = externalRef.substring(externalRef.indexOf('/') + 1);
        String brand = Arrays.stream(site.split(WORD_BOUNDARY))
                .filter(word -> !word.isEmpty())
                .filter(word -> !word.chars().allMatch(Character::isDigit))
                .filter(word -> !SITE_WORDS.contains(word.toLowerCase(Locale.ROOT)))
                .collect(Collectors.joining(" "));
        String brandKey = key(brand);
        if (brandKey.length() < MIN_BRAND_LENGTH || overlaps(brandKey, key(companyName))
                || overlaps(brandKey, key(tenant))) {
            return Optional.empty();
        }
        return Optional.of(brand);
    }

    private static boolean overlaps(String brandKey, String otherKey) {
        return !otherKey.isEmpty() && (otherKey.contains(brandKey) || brandKey.contains(otherKey));
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
