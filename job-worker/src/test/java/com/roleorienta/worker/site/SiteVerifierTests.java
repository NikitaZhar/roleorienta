package com.roleorienta.worker.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.TestHttpClients;
import com.roleorienta.worker.site.SiteVerifier.Check;
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
    private static final String REDIRECT = "redirect:";
    /** Видимый текст длиннее порога заглушки. */
    private static final String FILLER = "Vyrábame plastové diely pre automobilový priemysel a dodávame ich zákazníkom "
            + "v celej Európe už viac ako dvadsať rokov, s dôrazom na kvalitu, presnosť a spoľahlivosť dodávok "
            + "a na dlhodobé partnerstvá s našimi odberateľmi a dodávateľmi.";

    private final ExternalHttpClient httpClient = TestHttpClients.forLocalStub();
    /** Страница по пути: тело; нет — 404; {@code "403"} — 403; {@code "redirect:<путь>"} — 302 на путь. */
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
            if (page != null && page.startsWith(REDIRECT)) {
                exchange.getResponseHeaders().set("Location", page.substring(REDIRECT.length()));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
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
     * Название из двух слов: сайт подтверждается и первым словом ({@code gelateria.sk} для «Gelateria Paris»),
     * и двумя словами слитно в домене; текст сравнивается без диакритики («Móda Mora» → {@code moda.sk}). Правило
     * скрипта замера (§73).
     */
    @Test
    void firstWordOfNameConfirms() {
        pages.put("/", html("Gelateria", "<p>Gelateria Paris. " + FILLER + "</p>"));

        assertThat(verifier.verify("www.gelateria.sk", "", NUMBER, "Gelateria Paris, s.r.o.")).isEqualTo(Verdict.BRAND);
        assertThat(verifier.verify("www.gelateriaparis.sk", "", NUMBER, "Gelateria Paris, s.r.o."))
                .isEqualTo(Verdict.BRAND);

        pages.put("/", html("Móda Mora", "<p>Móda Mora. " + FILLER + "</p>"));
        assertThat(verifier.verify("www.moda.sk", "", NUMBER, "MÓDA MORA, a.s.")).isEqualTo(Verdict.BRAND);
    }

    /**
     * Правила бренда — по конечному адресу после переадресации: тот же текст, что подтверждает
     * {@code www.alfaplast.sk} без переадресации, после переадресации на чужой хост (заглушка, {@code 127.0.0.1})
     * сайт не подтверждает; адрес ответа — конечный.
     */
    @Test
    void brandCheckedOnFinalAddress() {
        pages.put("/", REDIRECT + "/home");
        pages.put("/home", html("Alfaplast", "<p>Alfaplast. " + FILLER + "</p>"));

        Check check = verifier.check("www.alfaplast.sk", "", NUMBER, NAME);

        assertThat(check.verdict()).isEqualTo(Verdict.OPENED);
        assertThat(check.location().getPath()).isEqualTo("/home");
    }

    /**
     * Короткая страница — заглушка, брендом не подтверждается; сайт не в зоне {@code .sk} без признака Словакии
     * — тоже нет, но бренд — первое слово его домена: похож на сайт группы.
     */
    @Test
    void brandNeedsSlovakSignalAndText() {
        pages.put("/", html("Alfaplast", "<p>Alfaplast. Plastic parts for cars.</p>"));
        assertThat(verify("alfaplast.sk")).isEqualTo(Verdict.OPENED);

        pages.put("/", html("Alfaplast", "<p>Alfaplast. " + FILLER + "</p>"));
        assertThat(verify("alfaplast.com")).isEqualTo(Verdict.GROUP);
    }

    /**
     * 403 — закрыт для программы; 404 — сайта нет. Регистрируемый домен — две последние части, у
     * {@code co.kr} — три.
     */
    @Test
    void closedAndGoneSites() {
        pages.put("/", "403");
        assertThat(verify("alfaplast.sk")).isEqualTo(Verdict.CLOSED);

        pages.remove("/");
        assertThat(verify("alfaplast.sk")).isEqualTo(Verdict.GONE);
        assertThat(SiteBrand.registrable("sk.firma.com")).isEqualTo("firma.com");
        assertThat(SiteBrand.registrable("www.yura.co.kr")).isEqualTo("yura.co.kr");
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

    /**
     * Адреса по названию: домены бренда в {@code .sk}, {@code .com} с путями, кадровые домены; «A &amp; B» — слитно.
     */
    @Test
    void addressesFromName() {
        assertThat(SiteBrand.addresses(NAME)).startsWith(new SiteAddress("www.alfaplast.sk", ""),
                new SiteAddress("www.alfaplast-slovakia.sk", ""), new SiteAddress("www.alfaplastslovakia.sk", ""),
                new SiteAddress("www.alfaplast.com", "sk/")).contains(new SiteAddress("kariera.alfaplast.sk", ""))
                .hasSize(12);
        assertThat(SiteBrand.addresses("Tate & Lyle Slovakia s.r.o.")).contains(new SiteAddress("www.tateandlyle.com", ""));
        assertThat(SiteBrand.brandHome("www.alfaplast.com", NAME)).isTrue();
        assertThat(SiteBrand.brandHome("www.alfaplast-slovakia.sk", NAME)).isFalse();
    }

    private Verdict verify(String host) {
        return verifier.verify(host, "", NUMBER, NAME);
    }

    private static String html(String title, String body) {
        return "<html><head><title>" + title + "</title></head><body>" + body + "</body></html>";
    }
}
