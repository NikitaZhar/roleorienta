package com.roleorienta.worker.adapters.workday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.http.AddressPolicy;
import com.roleorienta.worker.http.SourceHttpClient;
import com.roleorienta.worker.http.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkdayAdapter} против локального сервера-заглушки (как {@link
 * com.roleorienta.worker.http.SourceHttpClientFetchTest}): проверяются разбор списка
 * (POST), пагинация по {@code offset} и разбор детали (GET, JSON). Используется
 * permissive {@link AddressPolicy} — только чтобы поднять loopback-сервер в тесте.
 *
 * <p>Пути cxs заглушки: список — {@code /wday/cxs/acme/careers/jobs}, деталь —
 * {@code /wday/cxs/acme/careers/job/...}. Список отдаёт {@code total=40} и одну
 * непустую публикацию независимо от offset — этого достаточно, чтобы проверить обе
 * ветки курсора (есть следующая страница / нет).</p>
 */
class WorkdayAdapterTest {

    private static final String EXTERNAL_PATH = "/job/Bratislava/Senior-Java-Engineer_JR-1001";

    private HttpServer server;
    private volatile String lastAcceptLanguage;
    private WorkdayAdapter adapter;
    private Source source;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        // Список (POST): total=40 и одна публикация, независимо от тела запроса. Фасеты — как у
        // реального тенанта (§56): страны на верхнем уровне плюс вложенная группа локаций.
        server.createContext("/wday/cxs/acme/careers/jobs", exchange -> {
            lastAcceptLanguage = exchange.getRequestHeaders().getFirst("Accept-Language");
            drain(exchange.getRequestBody());
            String body = """
                    {"total":40,"jobPostings":[
                      {"title":"Senior Java Engineer","externalPath":"%s","postedOn":"Posted 3 Days Ago"}
                    ],"facets":[
                      {"facetParameter":"remoteType","values":[{"descriptor":"Flex","count":45}]},
                      {"facetParameter":"Location_Country","values":[
                        {"id":"bc33","descriptor":"United States of America","count":30},
                        {"id":"sk01","descriptor":"Slovakia","count":7}]},
                      {"facetParameter":"locationMainGroup","values":[
                        {"descriptor":"Locations","facetParameter":"locationCountry","values":[
                          {"id":"at01","descriptor":"Austria","count":3}]},
                        {"descriptor":"Locations","facetParameter":"locations","values":[
                          {"id":"l1","descriptor":"AUT.9.Vienna","count":3},
                          {"id":"l2","descriptor":"Arizona - Home Teleworkers","count":12}]}]}
                    ]}""".formatted(EXTERNAL_PATH);
            respond(exchange, body);
        });

        // Деталь (GET, JSON): локация + описание (HTML), без структурной зарплаты.
        server.createContext("/wday/cxs/acme/careers/job/", exchange -> {
            drain(exchange.getRequestBody());
            String body = """
                    {"jobPostingInfo":{
                      "location":"Bratislava, Slovakia",
                      "startDate":"2026-09-01",
                      "jobDescription":"<p>Spring Boot required. English required.</p>"
                    }}""";
            respond(exchange, body);
        });

        server.start();

        adapter = new WorkdayAdapter(new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)), 1000, 1000, 5));
        source = new Source();
        source.setBaseUrl("http://127.0.0.1:" + port);
        source.setExternalRef("acme/careers");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void listParsesPostingAndFields() {
        PostingsPage page = adapter.listPostings(source, null);

        assertEquals(1, page.postings().size());
        var posting = page.postings().get(0);
        assertEquals(EXTERNAL_PATH, posting.externalId());
        assertEquals("Senior Java Engineer", posting.rawTitle());
        assertTrue(posting.url().endsWith("/en-US/careers" + EXTERNAL_PATH),
                "ссылка строится как /en-US/<site><externalPath>: " + posting.url());
    }

    @Test
    void listReadsCountryFacetsIncludingNestedAndPinsEnglish() {
        PostingsPage page = adapter.listPostings(source, null);

        assertEquals(java.util.Map.of("United States of America", 30, "Slovakia", 7, "Austria", 3),
                page.countryCounts());
        assertEquals("en-US", lastAcceptLanguage, "названия фасетов сверяются по-английски");
        assertEquals(java.util.Map.of("AUT.9.Vienna", 3, "Arizona - Home Teleworkers", 12), page.locationCounts(),
                "вложенный фасет locations (§59)");
    }

    @Test
    void listPaginatesByOffsetUntilTotal() {
        // Первая страница (offset 0): следующая существует (20 < 40).
        assertEquals("20", adapter.listPostings(source, null).nextCursor());
        // Страница с offset 20: следующей нет (40 не < 40).
        assertNull(adapter.listPostings(source, "20").nextCursor());
    }

    @Test
    void detailParsesLocationAndDescriptionWithoutSalary() {
        FetchedPosting detail = adapter.getPosting(source, EXTERNAL_PATH);

        assertEquals("Bratislava, Slovakia", detail.rawLocation());
        assertTrue(detail.rawDescription().contains("Spring Boot required"),
                "описание снято из HTML: " + detail.rawDescription());
        // Структурной зарплаты у Workday нет — адаптер не выдумывает диапазон (§6, A09).
        assertNull(detail.compensation());
        assertNull(detail.rawCompensation());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void drain(InputStream in) throws java.io.IOException {
        in.readAllBytes();
        in.close();
    }
}
