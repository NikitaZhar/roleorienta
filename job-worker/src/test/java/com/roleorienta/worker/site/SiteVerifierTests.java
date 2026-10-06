package com.roleorienta.worker.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.TestHttpClients;
import com.roleorienta.worker.site.SiteVerifier.Verdict;
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
 * Проверка сайта ({@link SiteVerifier}) на заглушке: IČO на главной и на странице реквизитов, бренд, открытый
 * сайт без подтверждения, 403, 404.
 */
class SiteVerifierTests {

    private static final String NUMBER = "01234567";
    private static final String NAME = "Alfaplast Slovakia s.r.o.";
    /** Видимый текст длиннее порога заглушки. */
    private static final String FILLER = "Vyrábame plastové diely pre automobilový priemysel a dodávame ich zákazníkom "
            + "v celej Európe už viac ako dvadsať rokov, s dôrazom na kvalitu, presnosť a spoľahlivosť dodávok "
            + "a na dlhodobé partnerstvá s našimi odberateľmi a dodávateľmi.";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /** Страница по пути: тело; нет — 404; {@code "403"} — 403. */
    private final Map<String, String> pages = new HashMap<>();

    private HttpServer server;
    private SiteVerifier verifier;

    /**
     * @throws IOException порт не открылся
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String page = pages.get(exchange.getRequestURI().getPath());
            int status = page == null ? 404 : "403".equals(page) ? 403 : 200;
            byte[] bytes = status == 200 ? page.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        verifier = new SiteVerifier(httpClient,
                new SiteCheckProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
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
     * IČO рядом с подписью на главной (с пробелами) — {@code REGISTRATION_NUMBER}.
     */
    @Test
    void numberOnHomePage() {
        pages.put("/", html("Alfaplast", "<p>IČO: 01 234 567</p>"));

        assertThat(verify("alfaplast.sk")).isEqualTo(Verdict.REGISTRATION_NUMBER);
    }

    /**
     * IČO на странице «Kontakt» по ссылке с главной — {@code REGISTRATION_NUMBER}; номер в скрипте не считается.
     */
    @Test
    void numberOnLegalPage() {
        pages.put("/", html("Alfaplast", "<script>var ico = 'IČO: 01234567';</script><a href=\"/kontakt\">Kontakt</a>"));
        pages.put("/kontakt", html("Kontakt", "<p>IČO 01234567</p>"));

        assertThat(verify("alfaplast.sk")).isEqualTo(Verdict.REGISTRATION_NUMBER);
    }

    /**
     * IČO нет; бренд в домене {@code .sk} и отдельным словом в тексте — {@code BRAND}; тот же текст на домене
     * другой фирмы — открылся без подтверждения.
     */
    @Test
    void brandConfirmsSite() {
        pages.put("/", html("Alfaplast", "<p>Alfaplast. " + FILLER + "</p>"));

        assertThat(verify("www.alfaplast.sk")).isEqualTo(Verdict.BRAND);
        assertThat(verify("www.betaplast.sk")).isEqualTo(Verdict.OPENED);
    }

    /**
     * Сайт не в зоне {@code .sk} без признака Словакии брендом не подтверждается; короткая страница —
     * заглушка, тоже нет.
     */
    @Test
    void brandNeedsSlovakSignalAndText() {
        pages.put("/", html("Alfaplast", "<p>Alfaplast. Plastic parts for cars.</p>"));
        assertThat(verify("alfaplast.sk")).isEqualTo(Verdict.OPENED);

        pages.put("/", html("Alfaplast", "<p>Alfaplast. " + FILLER + "</p>"));
        assertThat(verify("alfaplast.com")).isEqualTo(Verdict.OPENED);
    }

    /**
     * 403 — закрыт для программы; 404 — сайта нет.
     */
    @Test
    void closedAndGoneSites() {
        pages.put("/", "403");
        assertThat(verify("alfaplast.sk")).isEqualTo(Verdict.CLOSED);

        pages.remove("/");
        assertThat(verify("alfaplast.sk")).isEqualTo(Verdict.GONE);
    }

    /**
     * Бренды названия: правовая форма и «Slovakia» отброшены; общие слова брендом не считаются.
     */
    @Test
    void brandKeysFromName() {
        assertThat(SiteBrand.keys(NAME)).containsExactly("alfaplast");
        assertThat(SiteBrand.keys("Železnice Slovenskej republiky")).containsExactly("zeleznice", "zelezniceslovenskej");
        assertThat(SiteBrand.keys("Obec Senec")).containsExactly("obecsenec");
    }

    private Verdict verify(String host) {
        return verifier.verify(host, NUMBER, NAME);
    }

    private static String html(String title, String body) {
        return "<html><head><title>" + title + "</title></head><body>" + body + "</body></html>";
    }
}
