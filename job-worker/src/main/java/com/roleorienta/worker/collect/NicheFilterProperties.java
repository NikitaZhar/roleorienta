package com.roleorienta.worker.collect;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ниша пилота и бюджет деталей для сбора (§63).
 *
 * <p>Все публикации рынка попадают в ленту (дёшево: заголовок и ссылка из списка), но
 * запрос <b>детали</b> — отдельный HTTP-вызов на каждую публикацию — делается только для
 * заголовков ниши и в пределах дневного бюджета на источник. Списки — временные слова-
 * признаки (пилот backend/JVM); в продукте ниша будет задаваться иначе (типовые названия
 * ролей, профиль пользователя) — механизм отбора от этого не меняется.</p>
 *
 * <p>Совпадение — целым словом (или фразой), без учёта регистра; {@code exclude} сильнее
 * {@code include}. Пустой {@code include} — ниша не ограничена (деталь для всех).</p>
 *
 * @param include           признаки заголовка ниши
 * @param exclude           признаки заголовка вне ниши (перекрывают {@code include})
 * @param dailyDetailBudget максимум заданий {@code FETCH_POSTING} на источник за сутки (UTC)
 */
@ConfigurationProperties(prefix = "app.collect.niche")
public record NicheFilterProperties(
        @DefaultValue({"Java", "Kotlin", "Scala", "JVM", "Spring", "Backend", "Back-end", "Software Engineer",
                "Software Developer", "Platform", "DevOps", "SRE", "Site Reliability", "Cloud", "Microservices",
                "Architect"}) List<String> include,
        @DefaultValue({"SAP", "Sales", "Marketing", "Clinical", "Intern", "Internship", "Accountant",
                "Accounting", "Technician"}) List<String> exclude,
        @DefaultValue("30") int dailyDetailBudget) {

    /**
     * Заголовок относится к нише.
     *
     * @param title заголовок публикации
     * @return {@code true}, если нет признака исключения и есть признак ниши (или ниша не задана)
     */
    public boolean matches(String title) {
        if (title == null) {
            return false;
        }
        Pattern out = wholeWords(exclude);
        if (out != null && out.matcher(title).find()) {
            return false;
        }
        Pattern in = wholeWords(include);
        return in == null || in.matcher(title).find();
    }

    private static Pattern wholeWords(List<String> terms) {
        String alternatives = terms.stream()
                .map(String::strip)
                .filter(term -> !term.isEmpty())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        if (alternatives.isEmpty()) {
            return null;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
