package com.roleorienta.worker.coverage;

import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.worker.coverage.KarriereClient.Listing;
import com.roleorienta.worker.coverage.KarriereClient.Listings;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Сопоставление публикации с выдачей площадки (A5, §82) — строгое, с честным «не проверено»
 * (ADR-15; нечёткое сопоставление — Этап 2, ADR-14).
 *
 * <ul>
 *   <li>заголовок совпал (после нормализации) у вакансии того же работодателя → {@code BOTH};</li>
 *   <li>заголовок совпал у другого юрлица → {@code UNKNOWN}: возможно, дочерняя компания или
 *       кадровое агентство (стенд: Ecolab ↔ «Ovivo Aqua Austria GmbH»);</li>
 *   <li>у того же работодателя есть почти такой же заголовок (общих слов ≥ 75%) → {@code UNKNOWN}
 *       («…Manager for Railway…» ↔ «…Manager Railway…»);</li>
 *   <li>выдача неполная → {@code UNKNOWN};</li>
 *   <li>иначе → {@code SITE_ONLY}: «не найдена на karriere.at при проверке [дата]».</li>
 * </ul>
 * <p>Нормализация заголовка: нижний регистр, без пометок пола ({@code (m/w/d)}, {@code (f/m/d)},
 * {@code (all genders)}), знаки препинания — пробелы. Работодатель совпадает, если юрлицо на
 * площадке содержит его имя (только буквы и цифры, без регистра): «Hitachi Rail Austria GmbH» ↔
 * «hitachi».</p>
 */
final class CoverageMatcher {

    /** Доля общих слов заголовка, начиная с которой вакансии считаются «почти одинаковыми». */
    static final double NEAR_TITLE_SIMILARITY = 0.75;

    /** Версия правил — в оценке покрытия. */
    static final String VERSION = "karriere-1";

    private static final Pattern GENDER_MARK = Pattern.compile(
            "\\((?:all genders|[mwfdx*](?:\\s*/\\s*[mwfdx*])+)\\)");

    private CoverageMatcher() {
    }

    /**
     * Итог сравнения одной публикации.
     *
     * @param state  состояние покрытия
     * @param reason видимая причина
     */
    record Verdict(CoverageState state, String reason) {
    }

    /**
     * Сравнивает публикацию с выдачей площадки.
     *
     * @param title     заголовок публикации
     * @param employer  имя работодателя (как искали на площадке)
     * @param listings  выдача площадки
     * @param checkedOn дата проверки (для формулировки отрицательного результата)
     * @return состояние и причина
     */
    static Verdict verdict(String title, String employer, Listings listings, String checkedOn) {
        String wanted = normalize(title);
        String owner = lettersAndDigits(employer);
        List<Listing> own = listings.items().stream()
                .filter(listing -> !owner.isEmpty() && lettersAndDigits(listing.company()).contains(owner))
                .toList();
        Optional<Listing> same = own.stream().filter(listing -> normalize(listing.title()).equals(wanted)).findFirst();
        if (same.isPresent()) {
            return new Verdict(CoverageState.BOTH, "найдена на " + KarriereClient.PLATFORM + " (id "
                    + same.get().id() + ") при проверке " + checkedOn);
        }
        Optional<Listing> foreign = listings.items().stream()
                .filter(listing -> normalize(listing.title()).equals(wanted)).findFirst();
        if (foreign.isPresent()) {
            return new Verdict(CoverageState.UNKNOWN, "тот же заголовок у другого юрлица на "
                    + KarriereClient.PLATFORM + ": " + foreign.get().company());
        }
        Optional<Listing> near = own.stream()
                .filter(listing -> similarity(wanted, normalize(listing.title())) >= NEAR_TITLE_SIMILARITY).findFirst();
        if (near.isPresent()) {
            return new Verdict(CoverageState.UNKNOWN, "похожая вакансия на " + KarriereClient.PLATFORM
                    + ": «" + near.get().title() + "» — нужна проверка");
        }
        if (!listings.complete()) {
            return new Verdict(CoverageState.UNKNOWN, "выдача " + KarriereClient.PLATFORM + " неполная");
        }
        return new Verdict(CoverageState.SITE_ONLY,
                "не найдена на " + KarriereClient.PLATFORM + " при проверке " + checkedOn);
    }

    /** Заголовок для сравнения: нижний регистр, без пометок пола, знаки — пробелы. */
    static String normalize(String title) {
        String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return GENDER_MARK.matcher(lower).replaceAll(" ").replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** Доля общих слов двух нормализованных заголовков (коэффициент Жаккара). */
    static double similarity(String left, String right) {
        Set<String> leftWords = words(left);
        Set<String> rightWords = words(right);
        if (leftWords.isEmpty() || rightWords.isEmpty()) {
            return 0;
        }
        Set<String> common = new HashSet<>(leftWords);
        common.retainAll(rightWords);
        Set<String> all = new HashSet<>(leftWords);
        all.addAll(rightWords);
        return (double) common.size() / all.size();
    }

    private static Set<String> words(String normalized) {
        return Arrays.stream(normalized.split(" ")).filter(word -> !word.isEmpty()).collect(Collectors.toSet());
    }

    private static String lettersAndDigits(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
