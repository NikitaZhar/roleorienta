package com.roleorienta.worker.adapter.greenhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.http.TestHttpClients;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Адаптер Greenhouse на заглушке: разбор списка, отказ источника, испорченный ответ.
 */
class GreenhouseAdapterTests {

    private final GreenhouseStub stub = new GreenhouseStub();
    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    private final GreenhouseAdapter adapter =
            new GreenhouseAdapter(httpClient, new GreenhouseProperties(stub.baseUrl()));

    /**
     * Остановка заглушки и клиента.
     */
    @AfterEach
    void stop() throws IOException {
        httpClient.close();
        stub.close();
    }

    /**
     * Все поля публикации берутся из ответа; отсутствующее место работы — {@code null}.
     */
    @Test
    void parsesJobs() {
        stub.respond(200, """
                {"jobs":[
                  {"id":101,"title":"Java Developer","absolute_url":"https://example.com/101",
                   "location":{"name":"Bratislava"},"content":"&lt;p&gt;Java&lt;/p&gt;"},
                  {"id":102,"title":"QA Engineer","absolute_url":"https://example.com/102"}
                ],"meta":{"total":2}}
                """);

        SourceReadResult result = adapter.read(GreenhouseStub.BOARD);

        assertThat(result).isEqualTo(SourceReadResult.Read.full(List.of(
                new FetchedPosting("101", "Java Developer", "https://example.com/101", "Bratislava",
                        "&lt;p&gt;Java&lt;/p&gt;"),
                new FetchedPosting("102", "QA Engineer", "https://example.com/102", null, null))));
    }

    /**
     * 503 — источник временно недоступен.
     */
    @Test
    void reportsUnavailableSource() {
        stub.respond(503, "");

        assertThat(adapter.read(GreenhouseStub.BOARD)).isEqualTo(new SourceReadResult.Unavailable(
                new HttpResult.TemporaryFailure("HTTP 503", Duration.ZERO)));
    }

    /**
     * Не-JSON в ответе 200 — временный отказ, а не пустой список.
     */
    @Test
    void treatsMalformedResponseAsTemporaryFailure() {
        stub.respond(200, "<html>maintenance</html>");

        SourceReadResult result = adapter.read(GreenhouseStub.BOARD);

        assertThat(result).isInstanceOf(SourceReadResult.Unavailable.class);
        assertThat(((SourceReadResult.Unavailable) result).failure()).isInstanceOf(HttpResult.TemporaryFailure.class);
    }
}
