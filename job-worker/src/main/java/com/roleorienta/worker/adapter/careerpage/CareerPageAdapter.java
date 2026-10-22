package com.roleorienta.worker.adapter.careerpage;

import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Адаптер собственной кадровой страницы компании (технический документ §5, «собственная страница вакансий»; §136):
 * HTML-список вакансий без системы найма и без разметки {@code JobPosting}. Доска — адрес страницы списка; правила
 * разбора — {@link CareerPageList}.
 *
 * <ul>
 *   <li>Публикация — ссылка списка: внешний id и ссылка — адрес страницы вакансии, название — текст ссылки, место —
 *       текст карточки без названия; карточки без текста — страна источника (страна компании, решение владельца
 *       2026-10-19): вакансия собственной страницы словацкой фирмы без указанного города — в Словакии.</li>
 *   <li>Следующие страницы списка — не больше {@link CareerPageProperties#maxPages()}; упор — неполное чтение
 *       ({@link PartialReason#PAGE_LIMIT}), отказ следующей страницы — {@link PartialReason#PAGE_FAILED}.</li>
 *   <li>Ссылки первой страницы списка не подтверждены текстом ({@link CareerPageList#confirmed}, §183) — вакансий не
 *       найдено: это не список вакансий (товары, меню, новости сайта).</li>
 *   <li>Вакансий не найдено: фраза «вакансий нет» — полное пустое чтение (прежние вакансии закрываются по общим
 *       правилам); иначе — неполное ({@link PartialReason#LIST_NOT_RECOGNIZED}): сбой разбора, а не закрытие.</li>
 *   <li>Отказ первой страницы — источник недоступен.</li>
 *   <li>Текст вакансии — деталью ({@link #detail}): видимый текст её страницы ({@code main}, иначе {@code body}).</li>
 * </ul>
 */
@Component
public class CareerPageAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "careerpage";

    private final ExternalHttpClient httpClient;
    private final CareerPageProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент (бюджет на хост, robots.txt)
     * @param properties потолок страниц списка и текста вакансии
     */
    public CareerPageAdapter(ExternalHttpClient httpClient, CareerPageProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SourceReadResult read(String board) {
        return read(board, null);
    }

    @Override
    public SourceReadResult read(String board, String country) {
        URI first = CareerPageList.uri(board);
        if (first == null) {
            return new SourceReadResult.Unavailable(new HttpResult.PermanentFailure(
                    HttpResult.Kind.BLOCKED, "Not a careers page URL: " + board));
        }
        HttpResult result = httpClient.get(first);
        if (!(result instanceof HttpResult.Success success)) {
            return new SourceReadResult.Unavailable(result);
        }
        Document page = Jsoup.parse(success.body(), success.locationOr(first).toString());
        boolean noVacancies = CareerPageList.saysNoVacancies(page);
        List<String> responses = new ArrayList<>(List.of(success.body()));
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>(List.of(first.toString(), page.location()));
        PartialReason partial = null;
        for (int number = 1; ; number++) {
            for (CareerPageList.Item item : CareerPageList.vacancies(page)) {
                postings.putIfAbsent(item.url(), new FetchedPosting(item.url(), item.title(), item.url(),
                        location(item.card(), country), null));
            }
            if (number == 1 && !postings.isEmpty()
                    && !CareerPageList.confirmed(List.copyOf(postings.keySet()), this::open)) {
                postings.clear();
                break;
            }
            Optional<URI> next = CareerPageList.nextPage(page, visited);
            if (next.isEmpty()) {
                break;
            }
            if (number == properties.maxPages()) {
                partial = PartialReason.PAGE_LIMIT;
                break;
            }
            visited.add(next.get().toString());
            HttpResult nextResult = httpClient.get(next.get());
            if (!(nextResult instanceof HttpResult.Success nextSuccess)) {
                partial = PartialReason.PAGE_FAILED;
                break;
            }
            responses.add(nextSuccess.body());
            page = Jsoup.parse(nextSuccess.body(), nextSuccess.locationOr(next.get()).toString());
        }
        if (postings.isEmpty() && partial == null && !noVacancies) {
            partial = PartialReason.LIST_NOT_RECOGNIZED;
        }
        return new SourceReadResult.Read(List.copyOf(postings.values()), partial, responses, Set.of(), Map.of());
    }

    /**
     * Текст вакансии — видимый текст её страницы ({@code main}, иначе {@code body}), не длиннее
     * {@link CareerPageProperties#maxContent()}; страница другого сайта не открывается.
     */
    @Override
    public FetchedPosting detail(String board, String externalId) {
        URI careerPage = CareerPageList.uri(board);
        URI vacancy = CareerPageList.uri(externalId);
        if (careerPage == null || vacancy == null
                || !CareerPageList.sameSite(careerPage.getHost(), vacancy.getHost())) {
            return null;
        }
        if (!(httpClient.get(vacancy) instanceof HttpResult.Success success)) {
            return null;
        }
        Document page = Jsoup.parse(success.body(), externalId);
        Element main = page.selectFirst("main");
        String text = (main != null ? main : page.body()).text();
        return new FetchedPosting(externalId, page.title(), externalId, null,
                text.length() > properties.maxContent() ? text.substring(0, properties.maxContent()) : text);
    }

    /**
     * @return открывшаяся страница с конечным адресом; пусто — не открылась
     */
    private Optional<Document> open(URI uri) {
        return httpClient.get(uri) instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(uri).toString())) : Optional.empty();
    }

    /**
     * Место публикации: текст карточки; пусто — страна источника по-английски ({@code Slovakia}); нет страны —
     * {@code null}.
     */
    private static String location(String card, String country) {
        if (!card.isBlank()) {
            return card;
        }
        return country == null ? null : Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
    }
}
