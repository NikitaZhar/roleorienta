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

    private static String lettersAndDigits(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
