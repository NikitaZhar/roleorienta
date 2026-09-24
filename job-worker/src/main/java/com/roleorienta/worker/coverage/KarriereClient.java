package com.roleorienta.worker.coverage;

import com.roleorienta.worker.http.SourceHttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Клиент площадки karriere.at — эталон сравнения покрытия для Австрии (A5, §82).
 *
 * <p>Поиск {@code https://www.karriere.at/jobs/<ключевое слово>} отдаёт HTML (встроенного JSON нет,
 * разведка 2026-09-24): активные вакансии — в блоке {@code .m-jobsSearchList__activeJobs}, у
 * каждой ссылка {@code /jobs/<id>}, заголовок и юрлицо; архивные («Diese Jobs hast du verpasst»)
 * — вне этого блока и не учитываются. Поиск полнотекстовый: в выдаче бывают и другие
 * работодатели — их отсекает {@link CoverageMatcher}. Число в заголовке выдачи («7 Hitachi Jobs»)
 * сверяется с разобранным: меньше — выдача неполная.</p>
 *
 * <p>Запрос — через {@link SourceHttpClient} (SSRF, темп на домен {@code karriere.at}, потолок
 * тела). Тексты вакансий не сохраняются — только факт наличия (правовое основание A24).</p>
 */
@Component
public class KarriereClient {

    /** Имя площадки в оценке покрытия. */
    public static final String PLATFORM = "karriere.at";

    /** Страна, которую охватывает площадка (как в {@code job_posting.country}). */
    public static final String COUNTRY = "Austria";

    private static final Pattern HEADER_COUNT = Pattern.compile("^\\s*(\\d+)\\s");

    private final SourceHttpClient httpClient;
    private final CoverageProperties properties;

    /**
     * @param httpClient единый HTTP-клиент воркера
     * @param properties адрес поиска площадки
     */
    public KarriereClient(SourceHttpClient httpClient, CoverageProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * Активные вакансии по ключевому слову (имени работодателя).
     *
     * @param keyword имя работодателя
     * @return вакансии и признак полноты выдачи
     * @throws IllegalStateException если страница не похожа на выдачу (сменилась вёрстка)
     */
    public Listings activeListings(String keyword) {
        return parse(httpClient.getBody(properties.searchUrl() + slug(keyword)));
    }

    /**
     * Слово поиска в адресе: нижний регистр, пробелы — дефисы, прочие знаки убраны.
     *
     * @param keyword имя работодателя
     * @return сегмент адреса (URL-кодированный)
     */
    static String slug(String keyword) {
        String cleaned = keyword.toLowerCase(Locale.ROOT).strip()
                .replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
        return URLEncoder.encode(cleaned, StandardCharsets.UTF_8);
    }

    /**
     * Разбор страницы выдачи.
     *
     * @param html страница
     * @return активные вакансии и признак полноты
     * @throws IllegalStateException если блока активных вакансий нет
     */
    static Listings parse(String html) {
        Document document = Jsoup.parse(html);
        Element active = document.selectFirst(".m-jobsSearchList__activeJobs");
        if (active == null) {
            throw new IllegalStateException("karriere.at: на странице нет блока активных вакансий");
        }
        List<Listing> listings = new ArrayList<>();
        for (Element item : active.select("li.m-jobsList__item")) {
            Element link = item.selectFirst("a.m-jobsListItem__titleLink");
            if (link == null) {
                continue; // рекламный блок «Jobalarm» в списке
            }
            Element company = item.selectFirst(".m-jobsListItem__companyName");
            listings.add(new Listing(idOf(link.attr("href")), link.text().strip(),
                    company == null ? "" : company.text().strip()));
        }
        Element header = document.selectFirst("h1.m-jobsListHeader__title");
        int announced = 0;
        if (header != null) {
            Matcher count = HEADER_COUNT.matcher(header.text());
            announced = count.find() ? Integer.parseInt(count.group(1)) : 0;
        }
        return new Listings(List.copyOf(listings), listings.size() >= announced);
    }

    private static String idOf(String href) {
        int slash = href.lastIndexOf('/');
        return slash >= 0 ? href.substring(slash + 1) : href;
    }

    /**
     * Вакансия на площадке.
     *
     * @param id      идентификатор на площадке
     * @param title   заголовок
     * @param company юрлицо
     */
    public record Listing(String id, String title, String company) {
    }

    /**
     * Выдача площадки.
     *
     * @param items    активные вакансии
     * @param complete разобраны все вакансии, объявленные в заголовке выдачи
     */
    public record Listings(List<Listing> items, boolean complete) {
    }
}
