package com.roleorienta.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.DiscoveryConfidence;
import com.roleorienta.core.domain.EmployerCandidate;
import com.roleorienta.core.domain.EmployerCandidateState;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.adapters.workday.WorkdayAdapter;
import com.roleorienta.worker.discovery.EmployerSourceRegistrar.Registration;
import com.roleorienta.worker.http.AddressPolicy;
import com.roleorienta.worker.http.SourceHttpClient;
import com.roleorienta.worker.http.SsrfGuard;
import com.roleorienta.worker.jobs.JobMessage;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Обнаружение Workday сквозь весь контур на заглушке (§35, §50): реальные
 * {@link WorkdayAdapter} + {@link SourceHttpClient} против локального Workday-стаба
 * проходят через настоящий {@link SourceAdapterRegistry} и
 * {@link DiscoverEmployerJobHandler}. Запись источника ({@link EmployerSourceRegistrar},
 * у него свой тест) и репозиторий кандидатов замоканы — проверяется именно решение
 * гейта уверенности для Workday: валидная непустая лента → HIGH → авто-подключение.
 *
 * <p>Отличие от {@link DiscoverEmployerJobHandlerTest} (там адаптер замокан): здесь
 * лента Workday читается по-настоящему (POST {@code /wday/cxs/acme/careers/jobs}),
 * то есть подтверждается, что новый вендор реально распознаётся контуром обнаружения.
 * Permissive {@link AddressPolicy} — только чтобы поднять loopback-сервер.</p>
 */
class WorkdayDiscoveryStubTest {

    private HttpServer server;
    private DiscoverEmployerJobHandler handler;
    private EmployerCandidateRepository candidateRepository;
    private EmployerSourceRegistrar registrar;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        // Стаб списка Workday (POST): total=2, две вакансии SK/AT и фасет стран — как у
        // реального многостранового тенанта (§56); без фасета Workday-доска не подключается
        // вслепую (§58).
        server.createContext("/wday/cxs/acme/careers/jobs", exchange -> {
            drain(exchange.getRequestBody());
            String body = """
                    {"total":2,"jobPostings":[
                      {"title":"Senior Java Engineer","externalPath":"/job/Bratislava/Senior-Java-Engineer_JR-1001"},
                      {"title":"Backend Engineer (JVM)","externalPath":"/job/Vienna/Backend-Engineer-JVM_JR-1002"}
                    ],"facets":[{"facetParameter":"Location_Country","values":[
                      {"id":"sk","descriptor":"Slovakia","count":1},
                      {"id":"at","descriptor":"Austria","count":1}]}]}""";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        SourceHttpClient httpClient = new SourceHttpClient(new SsrfGuard(new AddressPolicy(true)), 1000, 1000, 5);
        SourceAdapterRegistry registry = new SourceAdapterRegistry(List.of(new WorkdayAdapter(httpClient)));
        candidateRepository = mock(EmployerCandidateRepository.class);
        registrar = mock(EmployerSourceRegistrar.class);
        handler = new DiscoverEmployerJobHandler(registry, candidateRepository, registrar,
                DiscoveryMarketProperties.ofCountries(java.util.List.of("Slovakia", "Austria")));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void workdayCandidateAutoConnectsFromStubFeed() {
        when(candidateRepository.existsByProviderCodeAndSlug("workday", "acme/careers")).thenReturn(false);
        when(registrar.register("workday", "acme/careers", baseUrl, "acme/careers"))
                .thenReturn(new Registration(5L, 20L));

        String payload = "{\"providerCode\":\"workday\",\"slug\":\"acme/careers\",\"baseUrl\":\"" + baseUrl + "\"}";
        handler.handle(new JobMessage("key-workday-1", "DISCOVER_EMPLOYER", payload));

        // Лента прочитана по-настоящему и признана уверенной → авто-подключение источника.
        verify(registrar).register("workday", "acme/careers", baseUrl, "acme/careers");

        ArgumentCaptor<EmployerCandidate> captor = ArgumentCaptor.forClass(EmployerCandidate.class);
        verify(candidateRepository).save(captor.capture());
        EmployerCandidate saved = captor.getValue();
        assertEquals(EmployerCandidateState.CONFIRMED, saved.getState());
        assertEquals(DiscoveryConfidence.HIGH, saved.getConfidence());
        assertEquals(2, saved.getPostingCount());
        assertEquals(20L, saved.getSourceId());
        assertEquals(5L, saved.getCompanyId());
    }

    private static void drain(InputStream in) throws java.io.IOException {
        in.readAllBytes();
        in.close();
    }
}
