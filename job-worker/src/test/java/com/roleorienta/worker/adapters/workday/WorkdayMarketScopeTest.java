package com.roleorienta.worker.adapters.workday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.MarketScope;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.discovery.DiscoveryMarketProperties;
import com.roleorienta.worker.http.AddressPolicy;
import com.roleorienta.worker.http.SourceHttpClient;
import com.roleorienta.worker.http.SsrfGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Сбор Workday только по рынку (§62) против локального {@link HttpServer}: заглушка отвечает
 * по телу запроса — без фильтра отдаёт фасеты (реальная форма тенанта), с фильтром
 * {@code appliedFacets} — только отфильтрованные вакансии. Проверяется, что адаптер
 * выбирает id рыночных стран, при отсутствии фасета стран — id однозначно рыночных
 * локаций (американская «Vienna, VA» не берётся), несёт фильтр в курсоре и не читает
 * фасеты повторно, а без рынка отдаёт пустую страницу.
 */
class WorkdayMarketScopeTest {

    private static final MarketScope SK_AT = new DiscoveryMarketProperties(
            List.of("Slovakia", "Austria"),
            List.of("Slovakia", "Austria", "Bratislava", "Wien", "Graz", "AUT"),
            List.of("Vienna")).toScope();

    private HttpServer server;
    private Source source;
    private WorkdayAdapter adapter;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile Function<String, String> responder = body -> "{}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wday/cxs/acme/careers/jobs", this::handle);
        server.start();
        adapter = new WorkdayAdapter(new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)), 1000, 1000, 5));
        source = new Source();
        source.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        source.setExternalRef("acme/careers");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void filtersByMarketCountryIdsAndCarriesFilterInCursor() {
        responder = body -> body.contains("\"appliedFacets\":{}")
                ? """
                  {"total":370,"jobPostings":[],"facets":[{"facetParameter":"Location_Country","values":[
                    {"id":"us1","descriptor":"United States of America","count":300},
                    {"id":"sk1","descriptor":"Slovakia","count":25},
                    {"id":"at1","descriptor":"Austria","count":3}]}]}"""
                : """
                  {"total":28,"jobPostings":[
                    {"title":"Java Engineer","externalPath":"/job/Bratislava/Java_R1","locationsText":"Bratislava"}]}""";

        PostingsPage first = adapter.listPostings(source, null, SK_AT);

        assertEquals(2, bodies.size(), "фасеты без фильтра, затем список с фильтром");
        assertTrue(bodies.get(1).contains("\"Location_Country\":[\"sk1\",\"at1\"]"), bodies.get(1));
        assertEquals(1, first.postings().size());
        assertEquals("20|Location_Country=sk1,at1", first.nextCursor());

        bodies.clear();
        PostingsPage second = adapter.listPostings(source, first.nextCursor(), SK_AT);

        assertEquals(1, bodies.size(), "следующая страница — сразу с фильтром");
        assertTrue(bodies.get(0).contains("\"offset\":20") && bodies.get(0).contains("\"sk1\""), bodies.get(0));
        assertNull(second.nextCursor(), "40 не меньше 28 — страниц больше нет");
    }

    @Test
    void withoutCountryFacetFiltersByUnambiguousLocationIds() {
        responder = body -> body.contains("\"appliedFacets\":{}")
                ? """
                  {"total":432,"jobPostings":[],"facets":[{"facetParameter":"locationMainGroup","values":[
                    {"facetParameter":"locations","descriptor":"Locations","values":[
                      {"id":"la","descriptor":"Los Angeles, California","count":101},
                      {"id":"va","descriptor":"Vienna, VA","count":6},
                      {"id":"vie","descriptor":"Vienna, Austria","count":3}]}]}]}"""
                : """
                  {"total":3,"jobPostings":[{"title":"Backend Engineer","externalPath":"/job/Vienna/Backend_R2"}]}""";

        PostingsPage page = adapter.listPostings(source, null, SK_AT);

        assertTrue(bodies.get(1).contains("\"locations\":[\"vie\"]"), "только Vienna, Austria: " + bodies.get(1));
        assertEquals(1, page.postings().size());
    }

    @Test
    void noMarketValuesMeansNothingToCollect() {
        responder = body -> """
                {"total":1302,"jobPostings":[{"title":"x","externalPath":"/job/x"}],"facets":[
                  {"facetParameter":"Location_Country","values":[{"id":"us1","descriptor":"United States of America","count":1302}]}]}""";

        PostingsPage page = adapter.listPostings(source, null, SK_AT);

        assertTrue(page.postings().isEmpty());
        assertNull(page.nextCursor());
        assertEquals(1, bodies.size(), "фильтрованный список не запрашивается");
    }

    @Test
    void unrestrictedScopeIsPlainListing() {
        responder = body -> """
                {"total":1,"jobPostings":[{"title":"x","externalPath":"/job/x"}]}""";

        PostingsPage page = adapter.listPostings(source, null, MarketScope.ALL);

        assertEquals(1, page.postings().size());
        assertEquals(1, bodies.size());
        assertTrue(bodies.get(0).contains("\"appliedFacets\":{}"));
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        byte[] bytes = responder.apply(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
