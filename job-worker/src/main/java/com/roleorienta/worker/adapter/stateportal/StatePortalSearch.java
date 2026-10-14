package com.roleorienta.worker.adapter.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Общий список вакансий государственного портала и определение IČO работодателя (стенограмма §104, §105).
 *
 * <ul>
 *   <li>Список — JSON {@code /search/ponuky?sort=…&pageSize=N&pageNr=K} (K с 1); тот же запрос делает скрипт страницы
 *       {@code /pracovne-ponuky}. Запись {@code pracovnePonuky[]}: {@code uuid}, {@code nazovPracovnehoMiesta},
 *       {@code zamestnavatelObchodneMeno}, {@code miestoVykonuPrace}, {@code priznakZdroj} (3 — объявление площадки),
 *       {@code urlExternyPortal}. IČO в записи нет.</li>
 *   <li>Поиск работодателя по названию — JSON {@code /enum/vyhladavanie/zamestnavatelia?castNazvu=…}: пары
 *       {@code ico}, {@code nazov} (им пользуется фильтр «Názov zamestnávateľa» страницы).</li>
 *   <li>IČO своей вакансии портала — поле «IČO» её страницы {@code /pracovne-ponuky/<uuid>}; у объявления площадки
 *       страницы на портале нет.</li>
 * </ul>
 *
 * <p>Название — как в карточке портала: без пометки «muž/žena» к нему добавляется « (muž/žena)» (скрипт страницы,
 * {@code pp_applyRodovaRovnost}), поэтому оно совпадает с названием публикаций, прочитанных со страниц работодателя.</p>
 */
@Component
public class StatePortalSearch {

    /** {@code priznakZdroj} объявления площадки. */
    private static final int EXTERNAL_SOURCE = 3;
    /** Сколько работодателей просить у поиска по названию. */
    private static final int EMPLOYERS_ASKED = 10;
    private static final String GENDER_SUFFIX = " (muž/žena)";
    private static final Pattern GENDER_MARKER = Pattern.compile(
            "\\(\\s*(muž|žena)\\s*/\\s*(muž|žena)\\s*\\)|\\(\\s*[mfž]\\s*/\\s*[mfž]\\s*\\)|\\bmuž\\s*/\\s*žena\\b"
                    + "|\\bžena\\s*/\\s*muž\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REGISTRATION_NUMBER = Pattern.compile("\\d{1,8}");
    private static final int NUMBER_LENGTH = 8;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(StatePortalSearch.class);

    private final ExternalHttpClient httpClient;
    private final StatePortalProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент (вежливость к хосту, robots.txt)
     * @param properties адрес портала
     */
    public StatePortalSearch(ExternalHttpClient httpClient, StatePortalProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * Страница общего списка вакансий.
     *
     * @param page        номер страницы с 1
     * @param pageSize    записей на страницу
     * @param newestFirst {@code true} — «новые сверху» (по последнему изменению), иначе — старые сверху
     * @return вакансии страницы (меньше {@code pageSize} — последняя страница); запись без годного id пропускается;
     *         пусто — запрос не удался или ответ не JSON списка
     */
    public Optional<List<PortalOffer>> offers(int page, int pageSize, boolean newestFirst) {
        URI uri = URI.create(properties.baseUrl() + "/search/ponuky?sort=" + (newestFirst ? "OD_NAJNOVSICH"
                : "OD_NAJSTARSICH") + "&pageSize=" + pageSize + "&pageNr=" + page);
        Optional<JsonNode> body = json(uri);
        if (body.isEmpty() || !body.get().path("pracovnePonuky").isArray()) {
            return Optional.empty();
        }
        List<PortalOffer> offers = new ArrayList<>();
        for (JsonNode record : body.get().path("pracovnePonuky")) {
            boolean external = record.path("priznakZdroj").asInt() == EXTERNAL_SOURCE;
            String url = external ? text(record, "urlExternyPortal")
                    : properties.baseUrl() + "/pracovne-ponuky/" + text(record, "uuid");
            String externalId = url == null ? null : StatePortalAdapter.externalId(url);
            String title = text(record, "nazovPracovnehoMiesta");
            String employer = text(record, "zamestnavatelObchodneMeno");
            if (externalId == null || title == null || employer == null) {
                LOG.warn("State portal list page {}: record skipped: {}", page, record);
                continue;
            }
            offers.add(new PortalOffer(externalId, employer,
                    GENDER_MARKER.matcher(title).find() ? title : title + GENDER_SUFFIX,
                    text(record, "miestoVykonuPrace"), url));
        }
        return Optional.of(offers);
    }

    /**
     * Работодатели портала, найденные по части названия.
     *
     * @param name название работодателя
     * @return пары IČO — название; пусто — запрос не удался
     */
    public Optional<List<PortalEmployer>> employersNamed(String name) {
        URI uri = URI.create(properties.baseUrl() + "/enum/vyhladavanie/zamestnavatelia?castNazvu="
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&from=0&to=" + EMPLOYERS_ASKED);
        Optional<JsonNode> body = json(uri);
        if (body.isEmpty() || !body.get().isArray()) {
            return Optional.empty();
        }
        List<PortalEmployer> employers = new ArrayList<>();
        for (JsonNode employer : body.get()) {
            String number = text(employer, "ico");
            String found = text(employer, "nazov");
            if (number != null && found != null && REGISTRATION_NUMBER.matcher(number).matches()) {
                employers.add(new PortalEmployer(eightDigits(number), found));
            }
        }
        return Optional.of(employers);
    }

    /**
     * IČO своей вакансии портала — со страницы вакансии.
     *
     * @param uuid id вакансии портала
     * @return IČO; пусто — страница не получена или поля нет
     */
    public Optional<String> offerNumber(String uuid) {
        URI uri = URI.create(properties.baseUrl() + "/pracovne-ponuky/" + uuid);
        if (!(httpClient.get(uri) instanceof HttpResult.Success success)) {
            return Optional.empty();
        }
        Element number = StatePortalAdapter.value(Jsoup.parse(success.body()), "IČO");
        String text = number == null ? "" : number.text().trim();
        return REGISTRATION_NUMBER.matcher(text).matches() ? Optional.of(eightDigits(text)) : Optional.empty();
    }

    private Optional<JsonNode> json(URI uri) {
        if (!(httpClient.get(uri) instanceof HttpResult.Success success)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(success.body()));
        } catch (JsonProcessingException malformed) {
            LOG.warn("State portal {}: not JSON: {}", uri.getPath(), malformed.getOriginalMessage());
            return Optional.empty();
        }
    }

    /**
     * IČO из восьми цифр, как в списке работодателей портала и в доске его источника ({@code 123456} → {@code 00123456}).
     */
    private static String eightDigits(String number) {
        return "0".repeat(NUMBER_LENGTH - number.length()) + number;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }
}
