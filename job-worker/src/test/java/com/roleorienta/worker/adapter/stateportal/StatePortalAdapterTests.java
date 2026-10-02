package com.roleorienta.worker.adapter.stateportal;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.TestHttpClients;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер государственного портала на заглушке (разметка — как у портала на 2026-10-02): вакансии
 * работодателя — свои и объявления profesia.sk, страницами до объявленного числа; неполный список;
 * наличие вакансий; список работодателей.
 */
class StatePortalAdapterTests {

    private static final String KIA = "35876832";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();

    /** Страницы списка вакансий: ключ — «IČO:номер страницы». */
    private final Map<String, String> offerPages = new HashMap<>();
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
        server.createContext("/pracovne-ponuky", exchange -> respond(exchange,
                offerPages.getOrDefault(param(exchange, "firma") + ":" + param(exchange, "pageNr"), offers(0))));
        server.createContext("/zamestnavatelia", exchange -> respond(exchange,
                employerPages.getOrDefault(param(exchange, "pageNr"), "<html><body><main></main></body></html>")));
        server.start();
        adapter = new StatePortalAdapter(httpClient, new StatePortalProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), 2, 20, 150, Duration.ofDays(7)));
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
     * Три вакансии на двух страницах: своя вакансия портала (uuid, адрес без параметров поиска, место) и
     * объявления profesia.sk ({@code profesia:<номер>}); прочитано столько, сколько объявлено, — полное
     * чтение.
     */
    @Test
    void readsPortalAndPlatformOffersUpToDeclaredCount() {
        offerPages.put(KIA + ":1", offers(3, portalOffer("4f4762a0-b1ff-4955-91e1-a89792eddd81",
                "Riadiaci pracovník (manažér) v oblasti obchodu"), profesiaOffer("5354969", "Nákupca dielov")));
        offerPages.put(KIA + ":2", offers(3, profesiaOffer("5354970", "Technológ lisovne")));

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(KIA);

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).extracting(FetchedPosting::externalId)
                .containsExactly("4f4762a0-b1ff-4955-91e1-a89792eddd81", "profesia:5354969", "profesia:5354970");
        assertThat(read.postings().get(0)).isEqualTo(new FetchedPosting("4f4762a0-b1ff-4955-91e1-a89792eddd81",
                "Riadiaci pracovník (manažér) v oblasti obchodu (muž/žena)",
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/pracovne-ponuky/4f4762a0-b1ff-4955-91e1-a89792eddd81",
                "Teplička nad Váhom - Žilina", null));
        assertThat(read.postings().get(1).url()).isEqualTo("https://www.profesia.sk/O5354969");
    }

    /**
     * Список кончился раньше объявленного числа — неполное чтение: закрывать по нему нельзя.
     */
    @Test
    void shortListIsPartial() {
        offerPages.put(KIA + ":1", offers(5, profesiaOffer("1", "A"), profesiaOffer("2", "B")));

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(KIA);

        assertThat(read.complete()).isFalse();
        assertThat(read.partialReason()).isEqualTo(PartialReason.LIST_ENDED_EARLY);
        assertThat(read.postings()).hasSize(2);
    }

    /**
     * Нет вакансий — пустое полное чтение; наличие вакансий — по объявленному числу; не IČO — запроса нет.
     */
    @Test
    void answersWhetherEmployerHasOffers() {
        offerPages.put(KIA + ":1", offers(1, profesiaOffer("1", "A")));

        assertThat(((SourceReadResult.Read) adapter.read("12345678")).postings()).isEmpty();
        assertThat(adapter.hasPostingsIn(KIA, "SK")).contains(true);
        assertThat(adapter.hasPostingsIn("12345678", "SK")).contains(false);
        assertThat(adapter.hasPostingsIn("Kia", "SK")).isEmpty();
        assertThat(adapter.read("Kia")).isInstanceOf(SourceReadResult.Unavailable.class);
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
