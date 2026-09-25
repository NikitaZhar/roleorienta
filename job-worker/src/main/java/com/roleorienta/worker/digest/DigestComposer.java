package com.roleorienta.worker.digest;

import com.roleorienta.core.domain.CoverageState;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Текст письма-дайджеста (A7, §88): тема и простой текст без HTML. Два раздела — новые вакансии
 * отслеживаемых компаний (сначала «только на сайте компании» — ради них продукт, ADR-15) и
 * изменения известных вакансий (поля — понятными словами, одно слово на поле без повторов:
 * {@code salary_min} и {@code salary_max} — «зарплата»). В каждом разделе не больше
 * {@code maxItems} строк, остаток — строкой «и ещё N».
 */
final class DigestComposer {

    /** Формат даты в теме письма. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Поля публикации ({@code notification.field_name}) — слова для письма. */
    private static final Map<String, String> FIELD_LABELS = Map.of(
            "raw_location", "место", "city", "место", "country", "место",
            "work_modality", "формат работы",
            "salary_min", "зарплата", "salary_max", "зарплата", "salary_currency", "зарплата",
            "seniority", "уровень", "experience_years_min", "опыт");

    /** Оценка покрытия — пометка у новой вакансии; «не проверено» не помечается. */
    private static final Map<CoverageState, String> COVERAGE_LABELS = Map.of(
            CoverageState.SITE_ONLY, " [только на сайте компании]",
            CoverageState.BOTH, " [есть и на площадке]",
            CoverageState.ON_PLATFORM, " [на площадке]");

    private DigestComposer() {
    }

    /**
     * Письмо.
     *
     * @param subject тема
     * @param text    текст
     */
    record Message(String subject, String text) {
    }

    /**
     * Собирает письмо.
     *
     * @param content  содержимое окна (не пустое)
     * @param day      день дайджеста (в часовом поясе расписания) — в теме
     * @param maxItems строк в разделе, не меньше 1
     * @return тема и текст
     */
    static Message compose(DigestContent content, LocalDate day, int maxItems) {
        String subject = "roleorienta — дайджест за " + DAY.format(day) + ": новых вакансий "
                + content.newPostings().size() + ", изменений " + content.changes().size();
        StringBuilder text = new StringBuilder();
        section(text, "Новые вакансии отслеживаемых компаний", content.newPostings(), maxItems,
                posting -> line(posting.company(), posting.title(),
                        COVERAGE_LABELS.getOrDefault(posting.coverage(), ""), posting.url()));
        section(text, "Изменения в вакансиях", content.changes(), maxItems,
                posting -> line(posting.company(), posting.title(), " — изменено: " + labels(posting.fields()),
                        posting.url()));
        text.append("Подписки и уведомления — в приложении roleorienta.\n");
        return new Message(subject, text.toString());
    }

    private static <T> void section(StringBuilder text, String title, List<T> items, int maxItems,
                                    Function<T, String> format) {
        if (items.isEmpty()) {
            return;
        }
        text.append(title).append(" (").append(items.size()).append(")\n\n");
        items.stream().limit(maxItems).map(format).forEach(text::append);
        if (items.size() > maxItems) {
            text.append("… и ещё ").append(items.size() - maxItems).append('\n');
        }
        text.append('\n');
    }

    private static String line(String company, String title, String note, String url) {
        return "• " + company + ": " + title + note + "\n  " + Objects.toString(url, "") + "\n";
    }

    /** Поля — словами, без повторов, в алфавитном порядке; неизвестное поле — как есть. */
    static String labels(List<String> fields) {
        return String.join(", ", fields.stream()
                .map(field -> FIELD_LABELS.getOrDefault(field, field))
                .distinct().sorted().toList());
    }
}
