package com.roleorienta.worker.adapter.careerpage;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.TestHttpClients;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер собственной кадровой страницы (§136) на заглушке сайта: страницы списка, место и страна компании, «вакансий
 * нет», нераспознанный список, текст вакансии.
 */
class CareerPageAdapterTests {

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /** Страница по пути: тело; нет — 404. */
    private final Map<String, String> pages = new HashMap<>();

    private HttpServer server;
    private CareerPageAdapter adapter;

    /**
     * @throws IOException порт не открылся
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String page = pages.get(exchange.getRequestURI().getPath());
            byte[] bytes = page == null ? new byte[0] : page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(page == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        adapter = new CareerPageAdapter(httpClient, new CareerPageProperties(2, 30));
    }

    /**
     * @throws IOException ошибка закрытия клиента
     */
    @AfterEach
    void stop() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    /**
     * Две страницы списка — полное чтение; место — текст карточки, карточка без текста — страна источника.
     */
    @Test
    void readsListPagesWithPlacesAndCountryOfCompany() {
        pages.put("/kariera/", "<ul><li><a href=\"/kariera/casnik\">Čašník</a> Piešťany</li>"
                + "<li><a href=\"/kariera/kuchar\">Kuchár</a></li></ul><a href=\"/kariera/2\">Ďalej</a>");
        pages.put("/kariera/2", "<p>Koniec zoznamu</p>");

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(url("/kariera/"), "SK");

        assertThat(read.complete()).isTrue();
        assertThat(read.postings()).containsExactly(
                new FetchedPosting(url("/kariera/casnik"), "Čašník", url("/kariera/casnik"), "Piešťany", null),
                new FetchedPosting(url("/kariera/kuchar"), "Kuchár", url("/kariera/kuchar"), "Slovakia", null));
    }

    /**
     * Следующих страниц больше потолка (2) — неполное чтение: вакансии не закрываются.
     */
    @Test
    void pageLimitGivesPartialRead() {
        pages.put("/kariera/", list("a") + "<a href=\"/strana2\">Ďalej</a>");
        pages.put("/strana2", list("b") + "<a href=\"/strana3\">Ďalej</a>");
        pages.put("/strana3", list("c"));

        SourceReadResult.Read read = (SourceReadResult.Read) adapter.read(url("/kariera/"), "SK");

        assertThat(read.partialReason()).isEqualTo(PartialReason.PAGE_LIMIT);
        assertThat(read.postings()).hasSize(4);
    }

    /**
     * Ничего не распознано — неполное чтение (сбой разбора, не закрытие); «вакансий нет» — полное пустое; страница не
     * открылась — источник недоступен.
     */
    @Test
    void emptyListIsPartialUnlessPageSaysNoVacancies() {
        pages.put("/kariera/", "<p>Pošlite životopis na hr@firma.sk</p>");
        pages.put("/ziadne/", "<p>V súčasnosti neponúkame žiadne pracovné pozície.</p>");

        assertThat(((SourceReadResult.Read) adapter.read(url("/kariera/"), "SK")).partialReason())
                .isEqualTo(PartialReason.LIST_NOT_RECOGNIZED);
        SourceReadResult.Read none = (SourceReadResult.Read) adapter.read(url("/ziadne/"), "SK");
        assertThat(none.complete()).isTrue();
        assertThat(none.postings()).isEmpty();
        assertThat(adapter.read(url("/chyba/"), "SK")).isInstanceOf(SourceReadResult.Unavailable.class);
    }

    /**
     * Текст вакансии — видимый текст {@code main}, обрезанный до потолка (30); страница другого сайта не открывается.
     */
    @Test
    void detailIsVisibleTextOfVacancyPage() {
        pages.put("/kariera/casnik", "<header>Menu</header><main><h1>Čašník</h1><p>Hotel Aphrodite Palace, "
                + "Rajecké Teplice</p></main>");

        FetchedPosting detail = adapter.detail(url("/kariera/"), url("/kariera/casnik"));

        assertThat(detail.content()).isEqualTo("Čašník Hotel Aphrodite Palace,");
        assertThat(adapter.detail(url("/kariera/"), "https://example.org/kariera/casnik")).isNull();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static String list(String page) {
        return "<a href=\"/kariera/" + page + "1\">Pozícia " + page + "1</a>"
                + "<a href=\"/kariera/" + page + "2\">Pozícia " + page + "2</a>";
    }
}
