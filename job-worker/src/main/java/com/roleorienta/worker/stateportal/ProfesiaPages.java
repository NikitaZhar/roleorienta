package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.stateportal.StatePortalRepository.FoundSite;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Чтение profesia.sk для шагов 2 и 3 алгоритма кадровых страниц (§82, §83): объявление → страница компании и её
 * разделы → ссылка компании ({@link ProfesiaCompanyPage}), и карта сайта; страница объявления, которого нет на портале
 * ({@link #offer}, §219). Общий для заданий {@code PROFESIA_SITE}, {@code PROFESIA_LIST} и {@code PROFESIA_OFFERS}
 * (аудит §84: прежде одно задание пользовалось другим как HTTP-клиентом). robots.txt и пауза на хост соблюдаются
 * {@link ExternalHttpClient}.
 */
@Component
public class ProfesiaPages {

    private static final String PROOF = "PROFESIA";
    /** Длина {@code company_site.start_url}; длиннее — сайт записывается без стартовой страницы (аудит §84). */
    private static final int MAX_URL = 2000;

    private final ExternalHttpClient http;
    private final URI sitemap;
    private final int sitemapMaxBytes;

    /**
     * @param http            внешний HTTP-клиент (robots.txt, пауза на хост)
     * @param sitemap         адрес карты сайта profesia.sk
     * @param sitemapMaxBytes потолок тела ответа только для карты сайта (≈ 7 МБ; общий потолок — 5 МБ)
     */
    public ProfesiaPages(ExternalHttpClient http,
            @Value("${app.state-portal.profesia-sitemap-url:https://www.profesia.sk/sitemap.php}") URI sitemap,
            @Value("${app.state-portal.profesia-sitemap-max-bytes:16777216}") int sitemapMaxBytes) {
        this.http = http;
        this.sitemap = sitemap;
        this.sitemapMaxBytes = sitemapMaxBytes;
    }

    /**
     * @return карта сайта profesia.sk — ответ как есть (успех, временный или постоянный отказ)
     */
    public HttpResult sitemap() {
        return http.get(sitemap, sitemapMaxBytes);
    }

    /**
     * Объявление → страница компании и её разделы → выбранная ссылка компании: первая кадровая, иначе первая.
     * Временный отказ profesia.sk (429, 5xx, таймаут, очередь к хосту) на объявлении или странице компании —
     * {@link Unavailable}: работодателя нельзя отмечать проверенным; раздел с любым отказом пропускается (разделы
     * необязательны: сломанный раздел не держит работодателя). Постоянный отказ (404, запрет robots.txt) и
     * неразбираемый адрес объявления — {@link Missing}.
     *
     * @param adUrl объявление на profesia.sk
     * @return сайт или кадровая страница, «нет ссылки» или «profesia.sk временно недоступна»
     */
    public Lookup find(String adUrl) {
        Page ad = page(adUrl);
        if (!(ad instanceof Read adPage)) {
            return ad instanceof Failed failed ? new Unavailable(failed.failure()) : new Missing();
        }
        Optional<String> companyUrl = ProfesiaCompanyPage.companyPage(adPage.document());
        if (companyUrl.isEmpty()) {
            return new Missing();
        }
        Page company = page(companyUrl.get());
        if (!(company instanceof Read companyPage)) {
            return company instanceof Failed failed ? new Unavailable(failed.failure()) : new Missing();
        }
        List<String> links = new ArrayList<>(ProfesiaCompanyPage.companyLinks(companyPage.document()));
        for (String section : ProfesiaCompanyPage.sections(companyPage.document())) {
            if (page(section) instanceof Read read) {
                ProfesiaCompanyPage.companyLinks(read.document()).stream()
                        .filter(link -> !links.contains(link)).forEach(links::add);
            }
        }
        return ProfesiaCompanyPage.choose(links)
                .<Lookup>map(link -> new Found(site(link, companyPage.document().location())))
                .orElseGet(Missing::new);
    }

    /**
     * Страница объявления (§219): сведения ({@link ProfesiaOfferPage}); постоянный отказ (404, запрет robots.txt) или
     * неразбираемый адрес — {@link OfferGone}; страница без названия — {@link OfferUnparsed} (аудит §222); временный
     * отказ — {@link OfferUnavailable}.
     *
     * @param url адрес объявления из карты сайта
     * @return сведения, «нет объявления» или «profesia.sk временно недоступна»
     */
    public OfferRead offer(String url) {
        return switch (page(url)) {
            case Read read -> ProfesiaOfferPage.parse(read.document())
                    .<OfferRead>map(OfferFound::new).orElseGet(OfferUnparsed::new);
            case Failed failed -> new OfferUnavailable(failed.failure());
            case Gone gone -> new OfferGone();
        };
    }

    /**
     * Ссылка → сайт: хост ссылки; путь ссылки (кадровая страница) — стартовая страница, главная — без неё; адрес
     * длиннее колонки — без стартовой страницы. Ссылки приходят уже разобранными ({@link ProfesiaCompanyPage}).
     */
    static FoundSite site(String link, String evidenceUrl) {
        URI uri = URI.create(link);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        boolean home = (path.isEmpty() || "/".equals(path)) && uri.getRawQuery() == null;
        String startUrl = home || link.length() > MAX_URL ? null : link;
        return new FoundSite(uri.getHost().toLowerCase(Locale.ROOT), startUrl, evidenceUrl, PROOF);
    }

    private Page page(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException malformed) {
            return new Gone();
        }
        return switch (http.get(uri)) {
            case HttpResult.Success success ->
                    new Read(Jsoup.parse(success.body(), success.locationOr(uri).toString()));
            case HttpResult.TemporaryFailure temporary -> new Failed(temporary);
            case HttpResult.PermanentFailure permanent -> new Gone();
        };
    }

    /**
     * Итог поиска ссылки компании на profesia.sk.
     */
    public sealed interface Lookup permits Found, Missing, Unavailable {
    }

    /**
     * Ссылка найдена.
     *
     * @param site сайт или кадровая страница компании
     */
    public record Found(FoundSite site) implements Lookup {
    }

    /**
     * Ссылки нет: нет страницы компании, ссылок на ней, страница удалена или закрыта robots.txt.
     */
    public record Missing() implements Lookup {
    }

    /**
     * profesia.sk временно недоступна — повторить задание позже, работодателя не отмечать.
     *
     * @param failure временный отказ ({@code Retry-After} — в нём)
     */
    public record Unavailable(HttpResult.TemporaryFailure failure) implements Lookup {
    }

    /** Итог чтения страницы объявления (§219). */
    public sealed interface OfferRead permits OfferFound, OfferGone, OfferUnparsed, OfferUnavailable {
    }

    /** @param offer сведения объявления */
    record OfferFound(ProfesiaOfferPage.Offer offer) implements OfferRead {
    }

    /** Объявления нет: удалено или закрыто robots.txt. */
    public record OfferGone() implements OfferRead {
    }

    /** Страница получена, но без названия: баннер, капча, другая разметка — спросить позже. */
    public record OfferUnparsed() implements OfferRead {
    }

    /** @param failure временный отказ profesia.sk ({@code Retry-After} — в нём) */
    public record OfferUnavailable(HttpResult.TemporaryFailure failure) implements OfferRead {
    }

    /** Страница profesia.sk: прочитана, временный отказ или нет её. */
    private sealed interface Page permits Read, Failed, Gone {
    }

    /** @param document разобранная страница с адресом ответа */
    private record Read(Document document) implements Page {
    }

    /** @param failure временный отказ */
    private record Failed(HttpResult.TemporaryFailure failure) implements Page {
    }

    /** Постоянный отказ или неразбираемый адрес. */
    private record Gone() implements Page {
    }
}
