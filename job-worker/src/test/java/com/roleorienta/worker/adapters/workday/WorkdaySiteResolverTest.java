package com.roleorienta.worker.adapters.workday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * {@link WorkdaySiteResolver} — проверка кандидатов {@code site} через cxs-ленту (A1b)
 * против локальной заглушки тенанта ({@link HttpServer}). Заглушка знает ответы по пути
 * {@code /wday/cxs/acme/<site>/jobs}; всё остальное — 404. Проверяются: первый
 * подтверждённый кандидат, порядок и подстановка плейсхолдеров, отбраковка не-JSON /
 * чужого JSON / 4xx (в т.ч. реальный ответ Workday на несуществующий сайт — 404 S21),
 * проброс 429/5xx (сбой источника ≠ «сайта нет»), бюджет проб, дедуп кандидатов без
 * учёта регистра, сверка хоста с шаблоном без сети.
 * Permissive {@link AddressPolicy} — только для loopback-заглушки.
 */
class WorkdaySiteResolverTest {

    private static final String JOB_LIST = "{\"total\":2,\"jobPostings\":[{\"title\":\"Java\"}]}";
    private static final String EMPTY_LIST = "{\"total\":0,\"jobPostings\":[]}";
    /** Реальная форма ответа Workday cxs на несуществующий сайт (проверено 2026-09-23). */
    private static final String SITE_NOT_FOUND = "{\"errorCode\":\"S21\",\"errorCaseId\":\"X1\","
            + "\"httpStatus\":404,\"message\":\"not found: Job_Posting_Site_ID=External\",\"messageParams\":{}}";

    private HttpServer server;
    private String base;
    private final List<String> probedSites = new CopyOnWriteArrayList<>(); // пишет поток сервера
    private Map<String, Reply> replies = Map.of();

    private record Reply(int status, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void firstConfirmedCandidateWinsInConfiguredOrder() {
        replies = Map.of("Careers", new Reply(200, JOB_LIST), "Acme", new Reply(200, JOB_LIST));

        Optional<WorkdayBoard> board = resolver(List.of("External", "Careers", "{Tenant}"), 6)
                .resolveAt("acme", base);

        assertEquals(Optional.of(new WorkdayBoard("acme", "Careers", base)), board);
        assertEquals("acme/Careers", board.get().slug(), "slug = external_ref для WorkdayAdapter");
        assertEquals(List.of("External", "Careers"), probedSites, "после подтверждения пробы прекращаются");
    }

    @Test
    void tenantPlaceholderIsCapitalizedAndEmptyBoardStillCounts() {
        replies = Map.of("Acme", new Reply(200, EMPTY_LIST));

        assertEquals(Optional.of(new WorkdayBoard("acme", "Acme", base)),
                resolver(List.of("{Tenant}"), 6).resolveAt("acme", base),
                "пустая доска — всё равно доска; непустоту проверяет гейт уверенности");
    }

    @Test
    void nonListResponsesAreNotASite() {
        replies = Map.of(
                "Html", new Reply(200, "<html>career page</html>"),
                "OtherJson", new Reply(200, "{\"error\":\"unknown site\"}"),
                "External", new Reply(404, SITE_NOT_FOUND),
                "Gone", new Reply(422, "{}"));

        assertTrue(resolver(List.of("Html", "OtherJson", "External", "Gone", "Missing"), 10)
                .resolveAt("acme", base).isEmpty());
        assertEquals(5, probedSites.size());
    }

    @Test
    void sourceFailureIsPropagatedNotTreatedAsMissingSite() {
        replies = Map.of("Busy", new Reply(429, "{}"), "Broken", new Reply(503, "oops"));

        assertThrows(HttpClientErrorException.class,
                () -> resolver(List.of("Busy", "Careers"), 5).resolveAt("acme", base));
        assertThrows(HttpServerErrorException.class,
                () -> resolver(List.of("Broken", "Careers"), 5).resolveAt("acme", base));
    }

    @Test
    void stopsAtProbeBudget() {
        replies = Map.of("Third", new Reply(200, JOB_LIST));

        assertTrue(resolver(List.of("First", "Second", "Third"), 2).resolveAt("acme", base).isEmpty());
        assertEquals(List.of("First", "Second"), probedSites);
    }

    @Test
    void candidatesAreSubstitutedDedupedAndSanitized() {
        WorkdaySiteResolver resolver =
                resolver(List.of("{Tenant}", "{tenant}", " External ", "external", "{Tenant}_Careers", "../x", ""), 6);

        assertEquals(List.of("Workday", "External", "Workday_Careers"),
                resolver.candidatesFor("workday"),
                "дедуп без учёта регистра, первое написание сохраняется");
    }

    @Test
    void hostOutsideWorkdayPatternIsRejectedWithoutNetwork() {
        WorkdaySiteResolver resolver = new WorkdaySiteResolver(
                new SourceHttpClient(new SsrfGuard(), 500, 500, 5), List.of("External"), 6);

        assertTrue(resolver.resolve("myworkdayjobs.com").isEmpty());
        assertTrue(resolver.resolve("acme.example.com").isEmpty());
        assertTrue(resolver.resolve("acme.wd5.myworkdayjobs.com.evil.com").isEmpty());
        assertTrue(resolver.resolve("a.b.wd5.myworkdayjobs.com").isEmpty());
        assertTrue(resolver.resolve("*.wd5.myworkdayjobs.com").isEmpty());
    }

    private WorkdaySiteResolver resolver(List<String> candidates, int maxProbes) {
        return new WorkdaySiteResolver(
                new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)), 1000, 1000, 5),
                candidates, maxProbes);
    }

    private void handle(HttpExchange ex) throws IOException {
        ex.getRequestBody().readAllBytes();
        String prefix = "/wday/cxs/acme/";
        String path = ex.getRequestURI().getPath();
        Reply reply = new Reply(404, "not found");
        if ("POST".equals(ex.getRequestMethod()) && path.startsWith(prefix) && path.endsWith("/jobs")) {
            String site = path.substring(prefix.length(), path.length() - "/jobs".length());
            probedSites.add(site);
            reply = replies.getOrDefault(site, reply);
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
