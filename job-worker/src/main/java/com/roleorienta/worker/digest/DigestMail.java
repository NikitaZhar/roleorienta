package com.roleorienta.worker.digest;

import com.roleorienta.worker.digest.DigestRepository.Fresh;
import com.roleorienta.worker.digest.DigestRepository.Line;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.util.HtmlUtils;

/**
 * Письмо сводки на языке пользователя (бизнес-описание §6; стенограмма §164): тема с числом новых вакансий, строки
 * «работодатель · позиция · страна · дата открытия» (открытые позже сверху), «и ещё N», ссылка в приложение, как
 * изменить частоту. Два вида — текст и HTML (почтовые программы показывают тот, что умеют); значения в HTML
 * экранируются ({@link HtmlUtils#htmlEscape}) — название вакансии не становится разметкой.
 *
 * @param subject тема
 * @param text    текст
 * @param html    HTML
 */
record DigestMail(String subject, String text, String html) {

    /** Тексты письма по языку: {@code {n}} — число. */
    private static final Map<String, Map<String, String>> TEXTS = Map.of(
            "en", Map.of(
                    "subject", "roleorienta: {n} new vacancies",
                    "intro", "New vacancies matching your search since the last email: {n}.",
                    "more", "…and {n} more in the app.",
                    "open", "Open roleorienta",
                    "noEmployer", "Employer not specified",
                    "noCountry", "country not specified",
                    "found", "found {n}",
                    "footer", "You can change how often this email comes, or turn it off, in Settings."),
            "ru", Map.of(
                    "subject", "roleorienta: новых вакансий — {n}",
                    "intro", "Новых вакансий по вашему поиску с прошлого письма: {n}.",
                    "more", "…и ещё {n} в приложении.",
                    "open", "Открыть roleorienta",
                    "noEmployer", "Работодатель не указан",
                    "noCountry", "страна не указана",
                    "found", "найдена {n}",
                    "footer", "Как часто приходит письмо — или не присылать его — можно изменить в «Настройках»."));

    /**
     * @param language язык: {@code ru} — русский, иначе английский
     * @param fresh    новые вакансии (хотя бы одна)
     * @param appUrl   адрес приложения
     * @return письмо
     */
    static DigestMail of(String language, Fresh fresh, String appUrl) {
        Map<String, String> texts = TEXTS.getOrDefault(language, TEXTS.get("en"));
        Locale locale = Locale.forLanguageTag("ru".equals(language) ? "ru" : "en");
        DateTimeFormatter dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                .withZone(ZoneOffset.UTC);
        String count = String.valueOf(fresh.count());
        StringBuilder text = new StringBuilder(texts.get("intro").replace("{n}", count)).append("\n\n");
        StringBuilder html = new StringBuilder("<p>").append(escape(texts.get("intro").replace("{n}", count)))
                .append("</p><ul>");
        for (Line line : fresh.lines()) {
            String row = String.join(" · ", line.employer() == null ? texts.get("noEmployer") : line.employer(),
                    line.position(), line.countries().isEmpty() ? texts.get("noCountry")
                            : String.join(", ", line.countries()),
                    line.byEmployer() ? dates.format(line.opened())
                            : texts.get("found").replace("{n}", dates.format(line.opened())));
            text.append("• ").append(row).append('\n');
            html.append("<li>").append(escape(row)).append("</li>");
        }
        html.append("</ul>");
        long more = fresh.count() - fresh.lines().size();
        if (more > 0) {
            String rest = texts.get("more").replace("{n}", String.valueOf(more));
            text.append(rest).append('\n');
            html.append("<p>").append(escape(rest)).append("</p>");
        }
        text.append('\n').append(texts.get("open")).append(": ").append(appUrl).append("\n\n")
                .append(texts.get("footer")).append('\n');
        html.append("<p><a href=\"").append(escape(appUrl)).append("\">").append(escape(texts.get("open")))
                .append("</a></p><p style=\"color:#6E6960;font-size:12px\">").append(escape(texts.get("footer")))
                .append("</p>");
        return new DigestMail(texts.get("subject").replace("{n}", count), text.toString(), html.toString());
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
