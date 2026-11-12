package com.roleorienta.worker.adapter.careerpage;

import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.browser.LoadMore;
import com.roleorienta.worker.browser.PageRenderer;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
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
 *   <li>Публикация — ссылка списка ({@link CareerPageList#vacancies}): внешний id и ссылка — адрес страницы вакансии,
 *       название — заголовок в ссылке или в карточке кнопки «подробнее», иначе текст ссылки (§252), место — текст
 *       карточки без названия; карточки без текста — страна источника (страна компании, решение владельца
 *       2026-10-19), только у кадровой страницы в зоне этой страны ({@code .sk}; §299, решение владельца 2026-11-10):
 *       вакансия словацкой фирмы без указанного города — в Словакии, вакансия глобального кадрового сайта — без места.</li>
 *   <li>Следующие страницы списка — не больше {@link CareerPageProperties#maxPages()}; упор — неполное чтение
 *       ({@link PartialReason#PAGE_LIMIT}), отказ следующей страницы — {@link PartialReason#PAGE_FAILED}; следующая
 *       страница — ссылка «далее» или номер страницы (§255); «далее» листает скрипт ({@link CareerPageList#scriptNext})
 *       — хвост не прочитан, {@link PartialReason#PAGE_FAILED}.</li>
 *   <li>Ссылки первой страницы списка не подтверждены текстом ({@link CareerPageList#confirmed}, §183) — вакансий не
 *       найдено, чтение неполное, даже при фразе «вакансий нет» (аудит §197): страницы открылись, но без признаков
 *       вакансии — это не список вакансий (товары, меню, новости сайта), {@link PartialReason#LIST_NOT_RECOGNIZED};
 *       хоть одна не открылась — сбой чтения, {@link PartialReason#PAGE_FAILED} (аудит §313: к закрытию по §287 не
 *       засчитывается).</li>
 *   <li>Списка нет: фраза «вакансий нет» — полное пустое чтение (прежние вакансии закрываются по общим правилам, обвалом
 *       числа оно не считается — §257);
 *       иначе — неполное ({@link PartialReason#LIST_NOT_RECOGNIZED}): прежние публикации закрываются после
 *       нескольких таких чтений подряд (§287).</li>
 *   <li>Отказ первой страницы — источник недоступен.</li>
 *   <li>Первая страница по HTML сервера — без распознанного списка или с кнопкой «показать ещё»
 *       ({@link LoadMore}): она открывается браузером без окна с нажатием «показать ещё» ({@link PageRenderer#expanded},
 *       §241); построенная страница берётся, если вакансий на ней не меньше (AGEL: 7 в HTML, 196 после нажатий).</li>
 *   <li>Текст вакансии — деталью ({@link #detail}): видимый текст её страницы ({@code main}, иначе {@code body}).</li>
 * </ul>
 */
@Component
public class CareerPageAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "careerpage";
    /**
     * Сессионный параметр адреса: {@code search_id} или {@code utm_*} со значением; группа 1 — {@code ?} или
     * {@code &} перед ним.
     */
    private static final Pattern SESSION_PARAMETER = Pattern.compile("([?&])(?:search_id|utm_[a-z_]+|filter)=[^&#]*");
    /** Пустой хвост запроса после удаления параметров: {@code ?} или {@code &} в конце или перед якорем (группа 1). */
    private static final Pattern EMPTY_QUERY_END = Pattern.compile("[?&]+(#.*)?$");

    private final ExternalHttpClient httpClient;
    private final CareerPageProperties properties;
    private final PageRenderer renderer;

    /**
     * @param httpClient внешний HTTP-клиент (бюджет на хост, robots.txt)
     * @param properties потолок страниц списка и текста вакансии
     * @param renderer   браузер без окна ({@link PageRenderer#NONE} — без него)
     */
    public CareerPageAdapter(ExternalHttpClient httpClient, CareerPageProperties properties, PageRenderer renderer) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.renderer = renderer;
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
        Document served = Jsoup.parse(success.body(), success.locationOr(first).toString());
        Optional<Document> rendered = rendered(served);
        Document page = rendered.orElse(served);
        boolean noVacancies = CareerPageList.saysNoVacancies(page);
        List<String> responses = new ArrayList<>(List.of(success.body()));
        rendered.ifPresent(built -> responses.add(built.outerHtml()));
        // Кнопка «показать ещё» есть, а браузер список не раскрыл — прочитана лишь часть; браузер упёрся в предел
        // нажатий или времени — хвост не прочитан: прежние вакансии не закрываются (аудит §244).
        PartialReason expansion = rendered.isEmpty() && LoadMore.present(served) ? PartialReason.PAGE_FAILED
                : rendered.filter(built -> built.body().hasAttr(PageRenderer.INCOMPLETE)).isPresent()
                        ? PartialReason.PAGE_LIMIT : null;
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>(List.of(first.toString(), page.location()));
        PartialReason partial = null;
        boolean unconfirmed = false;
        // Страница вакансии не открылась при подтверждении списка (аудит §313): сбой чтения, а не «не список».
        AtomicBoolean notOpened = new AtomicBoolean();
        for (int number = 1; ; number++) {
            for (CareerPageList.Item item : CareerPageList.vacancies(page)) {
                String url = withoutSessionParameters(item.url());
                postings.putIfAbsent(url, new FetchedPosting(url, item.title(), url,
                        location(item.card(), country, first.getHost()), null));
            }
            if (number == 1 && !postings.isEmpty()
                    && !CareerPageList.confirmed(List.copyOf(postings.keySet()), uri -> opened(uri, notOpened))) {
                postings.clear();
                unconfirmed = true;
                break;
            }
            Optional<URI> next = CareerPageList.nextPage(page, visited);
            if (next.isEmpty()) {
                if (CareerPageList.scriptNext(page)) {
                    // Следующую страницу листает скрипт — хвост списка не прочитан, прежние вакансии не закрываются.
                    partial = PartialReason.PAGE_FAILED;
                }
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
        if (partial == null) {
            partial = unconfirmed && notOpened.get() ? PartialReason.PAGE_FAILED : expansion;
        }
        // Список есть, но не подтверждён: страницы вакансий открылись без признаков вакансии — «не список»; хоть одна
        // не открылась — выше, PAGE_FAILED. Не «вакансий нет», даже если на странице есть такая фраза: иначе прежние
        // вакансии закрылись бы полным чтением (аудит §197).
        if (postings.isEmpty() && partial == null && (!noVacancies || unconfirmed)) {
            partial = PartialReason.LIST_NOT_RECOGNIZED;
        }
        return new SourceReadResult.Read(List.copyOf(postings.values()), partial, responses, Set.of(), Map.of());
    }

    /**
     * Адрес вакансии без сессионных параметров ({@code search_id} кадровых страниц {@code *.profesia.sk} меняется при
     * каждом чтении, {@code utm_*} — метки рекламы, {@code filter} — метка условия у ссылки на ту же вакансию: maxins.sk
     * {@code ?filter=tpp}, §295): внешний id публикации — один и тот же при каждом чтении, а не новая
     * публикация (docs/vacancy-identity-plan.md §5, стенограмма §279). Остальные параметры остаются — они бывают
     * частью адреса вакансии ({@code ?id=N}).
     *
     * @param url адрес вакансии из списка
     * @return адрес без {@code search_id}, {@code utm_*} и {@code filter}
     */
    static String withoutSessionParameters(String url) {
        String cleaned = SESSION_PARAMETER.matcher(url).replaceAll("$1");
        return EMPTY_QUERY_END.matcher(cleaned.replace("?&", "?").replaceAll("&{2,}", "&")).replaceFirst("$1");
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
     * Первая страница списка (§241): по HTML сервера список не распознан или есть кнопка «показать ещё» — страница в
     * браузере без окна с нажатиями «показать ещё»; берётся, если вакансий на ней не меньше.
     *
     * @return страница из браузера; пусто — не понадобилась, не открылась или вакансий на ней меньше
     */
    private Optional<Document> rendered(Document page) {
        List<CareerPageList.Item> listed = CareerPageList.vacancies(page);
        if (!listed.isEmpty() && !LoadMore.present(page)) {
            return Optional.empty();
        }
        return renderer.expanded(URI.create(page.location()))
                .filter(built -> CareerPageList.vacancies(built).size() >= listed.size());
    }

    /** {@link #open}; не открылась — отметка в {@code failed}. */
    private Optional<Document> opened(URI uri, AtomicBoolean failed) {
        Optional<Document> page = open(uri);
        if (page.isEmpty()) {
            failed.set(true);
        }
        return page;
    }

    /**
     * @return открывшаяся страница с конечным адресом; пусто — не открылась
     */
    private Optional<Document> open(URI uri) {
        return httpClient.get(uri) instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(uri).toString())) : Optional.empty();
    }

    /**
     * Место публикации: текст карточки; карточки без текста — страна источника по-английски ({@code Slovakia}), только
     * если кадровая страница в зоне этой страны ({@code .sk}); иначе {@code null} (§299: глобальные кадровые сайты —
     * {@code globallogic.com}, {@code careers.ing.com} — давали вакансиям других стран Словакию; решение владельца
     * 2026-11-10, вариант 1').
     *
     * @param card    текст карточки вакансии
     * @param country страна источника; {@code null} — нет
     * @param host    хост кадровой страницы
     * @return место или {@code null}
     */
    static String location(String card, String country, String host) {
        if (!card.isBlank()) {
            return card;
        }
        boolean countryZone = country != null && host != null
                && host.toLowerCase(Locale.ROOT).endsWith("." + country.toLowerCase(Locale.ROOT));
        return countryZone ? Locale.of("", country).getDisplayCountry(Locale.ENGLISH) : null;
    }
}
