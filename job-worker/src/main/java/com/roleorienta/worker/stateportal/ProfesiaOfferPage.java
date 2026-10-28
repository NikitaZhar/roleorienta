package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.adapter.PostedDates;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Разбор страницы объявления profesia.sk (§219): название — {@code <h1>}; работодатель — из заголовка страницы
 * «название - работодатель | PROFESIA.SK»; место — разметка schema.org {@code itemprop="jobLocation"} →
 * {@code itemprop="address"}; дата размещения — {@code itemprop="datePosted"}; текст — разделы «о позиции» и
 * «требования» ({@code .job-info}, {@code .job-requirements}; раздел о компании не берётся). Чего нет, то {@code null}.
 */
final class ProfesiaOfferPage {

    /** Конец заголовка страницы. */
    private static final String SITE_SUFFIX = " | profesia.sk";
    private static final String TITLE_SEPARATOR = " - ";
    /** Длина {@code profesia_offer.employer_name} и {@code source.employer_name}. */
    private static final int MAX_EMPLOYER = 300;
    /** Длина {@code profesia_offer.title} и {@code location} (как у публикации, {@code FetchedPosting.MAX_TEXT}). */
    private static final int MAX_TEXT = 500;

    private ProfesiaOfferPage() {
    }

    /**
     * @param document страница объявления
     * @return сведения объявления; пусто — на странице нет названия ({@code <h1>}): не страница объявления
     */
    static Optional<Offer> parse(Document document) {
        Element heading = document.selectFirst("h1");
        String title = heading == null ? "" : heading.text().strip();
        if (title.isEmpty()) {
            return Optional.empty();
        }
        Element place = document.selectFirst("[itemprop=jobLocation] [itemprop=address]");
        Element posted = document.selectFirst("[itemprop=datePosted]");
        Elements sections = document.select(".job-info, .job-requirements");
        String content = sections.isEmpty() ? null : sections.text().strip();
        return Optional.of(new Offer(cut(title, MAX_TEXT), employer(document.title(), title),
                place == null ? null : cut(blank(place.text()), MAX_TEXT),
                content == null || content.isEmpty() ? null : content,
                posted == null ? null
                        : PostedDates.parse(posted.hasAttr("content") ? posted.attr("content") : posted.text())));
    }

    /**
     * Работодатель из заголовка страницы: «DevOps Manager - Brightpick s. r. o. | PROFESIA.SK» → «Brightpick s. r. o.».
     * Заголовок другого вида — {@code null}.
     */
    static String employer(String pageTitle, String title) {
        String text = pageTitle == null ? "" : pageTitle.strip();
        if (text.toLowerCase(Locale.ROOT).endsWith(SITE_SUFFIX)) {
            text = text.substring(0, text.length() - SITE_SUFFIX.length()).strip();
        }
        String prefix = title + TITLE_SEPARATOR;
        if (!text.startsWith(prefix)) {
            return null;
        }
        String employer = text.substring(prefix.length()).strip();
        return employer.isEmpty() ? null : cut(employer, MAX_EMPLOYER);
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    private static String blank(String value) {
        String text = value.strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Сведения объявления.
     *
     * @param title        название
     * @param employerName работодатель, как его называет объявление; {@code null} — не найден
     * @param location     место работы; {@code null} — нет
     * @param content      текст разделов о позиции и требованиях; {@code null} — нет
     * @param postedAt     дата размещения; {@code null} — нет
     */
    record Offer(String title, String employerName, String location, String content, Instant postedAt) {
    }
}
