package com.roleorienta.worker.adapter.stateportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.TestHttpClients;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер государственного портала на заглушке (разметка — как у портала на 2026-10-02): список работодателей, сайт
 * работодателя из детали его вакансии; чтение по работодателю не применяется (§107).
 */
class StatePortalAdapterTests {

    private static final String KIA = "35876832";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();

    /** Страницы списка вакансий: ключ — «IČO:номер страницы». */
    private final Map<String, String> offerPages = new HashMap<>();
    /** Детали вакансий портала по uuid. */
    private final Map<String, String> detailPages = new HashMap<>();
    /** Объявления profesia.sk по пути. */
    private final Map<String, String> profesiaPages = new HashMap<>();
    /** Страницы списка работодателей по номеру. */
    private final Map<String, String> employerPages = new HashMap<>();

    private HttpServer server;
    private StatePortalAdapter adapter;

    /**
     * Заглушка: {@code /pracovne-ponuky?firma=…&pageNr=…} и {@code /zamestnavatelia?pageNr=…}; прочих
     * страниц нет — пустой список.
     *
     * @throws IOException порт не открылся
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/pracovne-ponuky", exchange -> {
            String detail = exchange.getRequestURI().getPath().replaceFirst("^/pracovne-ponuky/?", "");
            respond(exchange, detail.isEmpty()
                    ? offerPages.getOrDefault(param(exchange, "firma") + ":" + param(exchange, "pageNr"), offers(0))
                    : detailPages.getOrDefault(detail, "<html><body></body></html>"));
        });
        server.createContext("/praca", exchange -> respond(exchange, profesiaPages.getOrDefault(
                exchange.getRequestURI().getPath(), "<html><body></body></html>")));
        server.createContext("/zamestnavatelia", exchange -> respond(exchange,
                employerPages.getOrDefault(param(exchange, "pageNr"), "<html><body><main></main></body></html>")));
        server.start();
        adapter = new StatePortalAdapter(httpClient, new StatePortalProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), 2,
                new StatePortalProperties.Task(150, Duration.ofMinutes(5)), Duration.ofDays(7), 10));
    }

    /**
     * Остановка заглушки и клиента.
     *
     * @throws IOException ошибка закрытия клиента
     */
    @AfterEach
    void stop() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    /**
     * Чтение источника портала по работодателю не применяется (вакансии — из общего списка, §107): вызов — ошибка кода.
     */
    @Test
    void readByEmployerIsNotUsed() {
        assertThatThrownBy(() -> adapter.read(KIA)).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Дата размещения со страницы объявления (§168): своя вакансия портала — «Dátum a čas vytvorenia»; объявление
     * profesia.sk — {@code itemprop="datePosted"}; без даты на странице и объявление другой площадки — даты нет.
     */
    @Test
    void readsPostedDateFromOfferPage() {
        String uuid = "4f4762a0-b1ff-4955-91e1-a89792eddd81";
        detailPages.put(uuid, "<html><body><main><dl class=\"vpm-data-panel\">"
                + field("Dátum a čas vytvorenia", "04.09.2026 o 09:58")
                + field("Naposledy aktualizované", "21.10.2026 o 14:59") + "</dl></main></body></html>");
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        profesiaPages.put("/praca/accenture/O5376258", "<div class=\"overall-info\"><strong>ID: </strong>5376258"
                + "<strong> Dátum zverejnenia: </strong> <span>16.10.2026</span>"
                + "<span class=\"hidden\" itemprop=\"datePosted\">2026-10-16</span></div>");

        assertThat(adapter.offerPostedDate(uuid, base + "/pracovne-ponuky/" + uuid))
                .contains(Instant.parse("2026-09-04T00:00:00Z"));
        assertThat(adapter.offerPostedDate("profesia:5376258", base + "/praca/accenture/O5376258"))
                .contains(Instant.parse("2026-10-16T00:00:00Z"));
        assertThat(adapter.offerPostedDate("profesia:1", base + "/praca/none/O1")).isEmpty();
        assertThat(adapter.offerPostedDate("0a0a0a0a-1111-4111-8111-000000000009", base)).isEmpty();
        assertThat(StatePortalAdapter.hasPostedDate("https://www.worki.sk/ponuka/1")).isFalse();
        assertThat(adapter.offerPostedDate("https://www.worki.sk/ponuka/1", "https://www.worki.sk/ponuka/1"))
                .isEmpty();
    }

    /**
     * Список работодателей: IČO и название с каждой карточки; страница без карточек — список кончился.
     */
    @Test
    void readsEmployerList() {
        employerPages.put("1", "<html><body><main>" + employer("Kia Slovakia s. r. o.", KIA, "6 aktívnych pracovných ponúk")
                + employer("AANI s.r.o.", "51752930", "0 aktívne pracovné ponuky") + "</main></body></html>");

        assertThat(adapter.employers(1)).contains(List.of(new PortalEmployer(KIA, "Kia Slovakia s. r. o."),
                new PortalEmployer("51752930", "AANI s.r.o.")));
        assertThat(adapter.employers(2)).isEqualTo(Optional.of(List.of()));
    }

    /**
     * Сайт работодателя из детали первой своей вакансии портала: «Internetová adresa» полным адресом (с путём,
     * без схемы — https); без неё — домен почты контакта со словом названия; почта на общем сервисе или на
     * чужом домене (бухгалтер), IČO другой фирмы или только объявления площадки — сайта нет.
     */
    @Test
    void findsEmployerSiteInOfferDetail() {
        String uuid = "4f4762a0-b1ff-4955-91e1-a89792eddd81";
        String detailUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/pracovne-ponuky/" + uuid;
        offerPages.put(KIA + ":1", offers(2, profesiaOffer("1", "A"), portalOffer(uuid, "B")));

        detailPages.put(uuid, detail(KIA, "<a href=\"https://www.kia.sk/sk\">www.kia.sk</a>", "kia.com"));
        assertThat(adapter.employerSite(KIA)).contains(new PortalSite("https://www.kia.sk/sk", null, detailUrl));

        detailPages.put(uuid, detail(KIA, "www.kia.sk", "kia.com"));
        assertThat(adapter.employerSite(KIA)).contains(new PortalSite("https://www.kia.sk", null, detailUrl));

        detailPages.put(uuid, detail(KIA, null, "kia.sk"));
        assertThat(adapter.employerSite(KIA)).contains(new PortalSite(null, "kia.sk", detailUrl));

        detailPages.put(uuid, detail(KIA, null, "gmail.com"));
        assertThat(adapter.employerSite(KIA)).contains(PortalSite.NONE);

        detailPages.put(uuid, detail(KIA, null, "ucto-plus.sk"));
        assertThat(adapter.employerSite(KIA)).contains(PortalSite.NONE);

        detailPages.put(uuid, detail("12345678", null, "kia.sk"));
        assertThat(adapter.employerSite(KIA)).contains(PortalSite.NONE);

        offerPages.put(KIA + ":1", offers(1, profesiaOffer("1", "A")));
        assertThat(adapter.employerSite(KIA)).contains(PortalSite.NONE);
    }

    /**
     * Деталь вакансии портала: поля работодателя ({@code dt}/{@code dd}) и почта контакта, которую
     * собирает скрипт.
     */
    private static String detail(String number, String web, String mailHost) {
        return "<html><body><main><dl class=\"vpm-data-panel\">"
                + field("Názov spoločnosti", "Kia Slovakia s. r. o.") + field("IČO", number)
                + (web == null ? "" : field("Internetová adresa", web))
                + field("Kontaktná osoba", "<span>Ján Novák</span><br><span><script> { let name = \"jan.novak\";"
                        + " let host = \"" + mailHost + "\"; if (host !== '' && name !== '') document.write(name + \"@\""
                        + " + host); } </script></span>")
                + "</dl></main></body></html>";
    }

    private static String field(String key, String value) {
        return "<div class=\"vpm-data-panel__row\"><dt class=\"vpm-data-panel__key\">" + key
                + "</dt><dd class=\"vpm-data-panel__value\">" + value + "</dd></div>";
    }

    private static String offers(int declared, String... cards) {
        return "<html><body><main><p><span>" + declared + " pracovných ponúk,</span> 1 zamestnávateľ</p>"
                + "<span>Nenašli sa žiadne pracovné ponuky, ktoré by vyhovovali zadaným kritériám</span><div>"
                + String.join("", cards) + "</div></main></body></html>";
    }

    private static String portalOffer(String uuid, String title) {
        return "<a href=\"/pracovne-ponuky/" + uuid + "?pageSize=50&amp;firma=" + KIA + "\" class=\"govuk-signpost\">"
                + "<div class=\"govuk-signpost__container\"><div><h3 class=\"govuk-signpost__title\"><span>" + title
                + "</span> (muž/žena) </h3><p class=\"govuk-signpost__description vpm-font-bold vpm-mb-1\">Kia Slovakia"
                + " s. r. o.</p><p class=\"govuk-signpost__description vpm-mb-1\">Teplička nad Váhom - Žilina</p>"
                + "<p class=\"govuk-signpost__description vpm-mb-1\"><span>základná zložka mzdy 6 697 €</span></p>"
                + "</div></div></a>";
    }

    private static String profesiaOffer(String number, String title) {
        return "<div><div class=\"govuk-signpost__container\"><div><h3 class=\"govuk-signpost__title\"><span>" + title
                + "</span></h3><p class=\"govuk-signpost__description vpm-font-bold vpm-mb-1\">Kia Slovakia s. r. o.</p>"
                + "<p class=\"govuk-signpost__description vpm-mb-1\">Teplička nad Váhom - Žilina</p>"
                + "<div class=\"vpm-mt-2 vpm-mb-0\"><a href=\"https://www.profesia.sk/O" + number
                + "\" class=\"govuk-link\"><span>Zobraziť na portáli profesia.sk</span></a></div></div></div></div>";
    }

    private static String employer(String name, String number, String offers) {
        return "<div><h3 class=\"govuk-signpost__title\">" + name + "</h3>"
                + "<p class=\"govuk-signpost__description vpm-font-bold vpm-mb-1\">IČO: " + number + "</p>"
                + "<p class=\"govuk-signpost__description vpm-mb-1\"><span><span>" + offers + "</span></span></p></div>";
    }

    private static String param(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        for (String pair : query == null ? new String[0] : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return "";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
