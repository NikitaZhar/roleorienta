package com.roleorienta.worker.discovery;

import com.roleorienta.worker.adapters.BoardProfile;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Проверка принадлежности доски работодателю в гейте обнаружения (A2, §79).
 *
 * <p>У Workday доска — тенант, выданный самому клиенту: поддомен и есть владелец. Имени
 * работодателя провайдер не отдаёт, поэтому принадлежность подтверждается косвенно, по
 * описанию со страницы доски ({@link BoardProfile}):</p>
 * <ul>
 *   <li>описания нет — владелец не подтверждён;</li>
 *   <li>в описании есть признак кадрового агентства ({@code agencyMarkers}) — доска может
 *       публиковать чужие вакансии (работодатель ≠ владелец доски);</li>
 *   <li>описание не называет владельца: тенант не встречается в тексте, если сравнивать
 *       только буквы и цифры без учёта регистра («tobiidynavox» ↔ «Tobii Dynavox»).</li>
 * </ul>
 * <p>Совпавшее написание владельца — имя новой компании ({@link #ownerName}, A3, §80).</p>
 * <p>Любое сомнение — не отказ: кандидат уходит на ручную проверку, а не подключается
 * автоматически.</p>
 *
 * @param agencyMarkers признаки кадрового агентства в описании (подстрока без учёта регистра)
 */
@ConfigurationProperties(prefix = "app.discovery.ownership")
public record BoardOwnershipProperties(
        @DefaultValue({"staffing", "recruitment agency", "recruiting agency", "employment agency",
                "personalvermittlung", "personaldienstleist", "zeitarbeit", "arbeitskräfteüberlassung",
                "on behalf of our client"}) List<String> agencyMarkers) {

    /** Короче — тенант не отличить от случайного совпадения букв в тексте. */
    private static final int MIN_OWNER_LENGTH = 3;

    /**
     * Сомнение в принадлежности доски.
     *
     * @param profile сведения о доске от провайдера
     * @return причина сомнения; пусто — принадлежность подтверждена
     */
    public Optional<String> doubt(BoardProfile profile) {
        String description = profile.description() == null ? "" : profile.description();
        if (description.isBlank()) {
            return Optional.of("владелец доски не подтверждён: у страницы доски нет описания");
        }
        String lower = description.toLowerCase(Locale.ROOT);
        for (String marker : agencyMarkers) {
            if (lower.contains(marker.strip().toLowerCase(Locale.ROOT))) {
                return Optional.of("похоже на кадровое агентство (в описании «" + marker.strip() + "»)");
            }
        }
        String owner = lettersAndDigits(profile.owner());
        if (owner.length() < MIN_OWNER_LENGTH || !lettersAndDigits(description).contains(owner)) {
            return Optional.of("описание доски не называет владельца «" + profile.owner() + "»");
        }
        return Optional.empty();
    }

    /**
     * Имя работодателя из описания доски (A3, §80): то написание владельца, которое нашла
     * проверка {@link #doubt} — фрагмент описания с начала слова, чьи буквы и цифры без учёта
     * регистра совпадают с тенантом («tobiidynavox» → «Tobii Dynavox», «iqvia» → «IQVIA»).
     *
     * @param profile сведения о доске от провайдера
     * @return имя как в описании; пусто — владелец в описании не найден
     */
    public Optional<String> ownerName(BoardProfile profile) {
        String owner = lettersAndDigits(profile.owner());
        String text = profile.description();
        if (owner.length() < MIN_OWNER_LENGTH || text == null) {
            return Optional.empty();
        }
        for (int start = 0; start < text.length(); start++) {
            if (isWordStart(text, start)) {
                Optional<String> name = matchFrom(text, start, owner);
                if (name.isPresent()) {
                    return name;
                }
            }
        }
        return Optional.empty();
    }

    /** Фрагмент от {@code start}, буквы и цифры которого (без регистра) равны {@code owner}. */
    private static Optional<String> matchFrom(String text, int start, String owner) {
        StringBuilder seen = new StringBuilder();
        for (int end = start; end < text.length(); end++) {
            char current = text.charAt(end);
            if (Character.isLetterOrDigit(current)) {
                seen.append(Character.toLowerCase(current));
                if (!owner.startsWith(seen.toString())) {
                    return Optional.empty();
                }
                if (seen.length() == owner.length()) {
                    return Optional.of(text.substring(start, end + 1));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isWordStart(String text, int index) {
        return Character.isLetterOrDigit(text.charAt(index))
                && (index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1)));
    }

    private static String lettersAndDigits(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
