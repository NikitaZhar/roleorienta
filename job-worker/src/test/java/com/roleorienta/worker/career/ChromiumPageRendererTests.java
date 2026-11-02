package com.roleorienta.worker.career;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.http.ExternalHttpProperties;
import com.roleorienta.worker.http.PolitenessProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Браузер без окна (§236) — настоящий Chromium (Playwright скачивает его при первом запуске) и сайт-заглушка на
 * встроенном HTTP-сервере JDK: ссылку «Kariéra» строит скрипт — после выполнения она на странице; переадресация
 * скриптом — конечный адрес страницы; внутренний адрес при запрете внутренних адресов не открывается.
 */
class ChromiumPageRendererTests {

    private static final String MENU_SCRIPT = "<html><body><nav id=\"menu\"></nav><script>"
            + "const link = document.createElement('a'); link.href = '/spolocnost/ponuka'; link.textContent = 'Kariéra';"
            + "document.getElementById('menu').appendChild(link);</script></body></html>";
    private static final String REDIRECT_SCRIPT = "<html><body>Redirecting…<script>location.replace('/sk/');</script>"
            + "</body></html>";
    private static final String TARGET = "<html><body><h1>Vitajte</h1></body></html>";

    private static HttpServer site;
    private static String origin;

    /**
     * Сайт-заглушка: главная с меню из скрипта, переадресация скриптом и её цель.
     *
     * @throws IOException сервер не запустился
     */
    @BeforeAll
    static void startSite() throws IOException {
        site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String page = "/".equals(path) ? MENU_SCRIPT : "/redirect".equals(path) ? REDIRECT_SCRIPT : TARGET;
            byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        site.start();
        origin = "http://127.0.0.1:" + site.getAddress().getPort();
    }

    /**
     * Остановка сайта.
     */
    @AfterAll
    static void stopSite() {
        site.stop(0);
    }

    /**
     * Меню из скрипта и переадресация скриптом видны после выполнения скриптов; у выключенного браузера — пусто.
     *
     * @throws InterruptedException остановка браузера прервана
     */
    @Test
    void rendersScriptMenuAndScriptRedirect() throws InterruptedException {
        ChromiumPageRenderer renderer = renderer(true, true);
        try {
            Optional<Document> home = renderer.render(URI.create(origin + "/"));
            assertThat(home).isPresent();
            assertThat(home.get().select("a[href]").eachAttr("abs:href")).containsExactly(origin + "/spolocnost/ponuka");
            assertThat(CareerLinks.careerPage(home.get(), URI.create(origin).getAuthority()))
                    .contains(origin + "/spolocnost/ponuka");

            Optional<Document> redirected = renderer.render(URI.create(origin + "/redirect"));
            assertThat(redirected).map(Document::location).contains(origin + "/sk/");
        } finally {
            renderer.destroy();
        }
        assertThat(renderer(false, true).render(URI.create(origin + "/"))).isEmpty();
    }

    /**
     * Внутренние адреса запрещены (как у внешнего HTTP-клиента) — страница на 127.0.0.1 не открывается.
     *
     * @throws InterruptedException остановка браузера прервана
     */
    @Test
    void doesNotOpenPrivateAddress() throws InterruptedException {
        ChromiumPageRenderer renderer = renderer(true, false);
        try {
            assertThat(renderer.render(URI.create(origin + "/"))).isEmpty();
        } finally {
            renderer.destroy();
        }
    }

    private static ChromiumPageRenderer renderer(boolean enabled, boolean allowPrivate) {
        return new ChromiumPageRenderer(new CareerRenderProperties(enabled, Duration.ofSeconds(20)),
                new ExternalHttpProperties(Duration.ofSeconds(5), Duration.ofSeconds(20), 5, 5_242_880, allowPrivate),
                new PolitenessProperties("Roleorienta/0.1 (test)", Duration.ZERO, Duration.ofSeconds(30), List.of(),
                        List.of()));
    }
}
