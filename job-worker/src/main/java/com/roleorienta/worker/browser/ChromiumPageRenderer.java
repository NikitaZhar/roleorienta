package com.roleorienta.worker.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import com.roleorienta.worker.http.AddressPolicy;
import com.roleorienta.worker.http.ExternalHttpProperties;
import com.roleorienta.worker.http.PolitenessProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Страница после выполнения скриптов в Chromium без окна через Playwright (§236, §241; https://playwright.dev/java/).
 *
 * <ul>
 *   <li><b>Один поток.</b> Объекты Playwright не потокобезопасны: браузер создаётся и используется в одном своём потоке
 *       ({@code chromium-renderer}); проверки сайтов идут параллельно и ждут своей очереди. Вызов, который не дождался
 *       (срок вышел), из очереди не исполняется.</li>
 *   <li><b>Браузер один</b> на job-worker, создаётся при первой странице (тогда же Playwright скачивает Chromium, один
 *       раз), на страницу — свой чистый профиль, закрывается после неё; сбой браузера — браузер пересоздаётся.</li>
 *   <li><b>Запросы страницы</b> — только http(s) к разрешённым адресам ({@link AddressPolicy}, как у внешнего
 *       HTTP-клиента: не локальные и не внутренние сети); картинки, шрифты, видео не загружаются. User-Agent — общий
 *       ядра. Страница открывается, только если тот же адрес уже открылся обычным запросом (robots.txt и бюджет хоста
 *       проверены там). Переходы по перенаправлению и веб-сокеты мимо этого фильтра не проходят — Playwright их
 *       не перехватывает: если такой запрос ушёл на запрещённый адрес, страница отбрасывается целиком (её содержимое не
 *       попадает в данные). Сам запрос при этом уже отправлен — остаточный риск; как и подмена адреса в DNS между
 *       проверкой хоста и запросом браузера (аудит §244). Service worker'ы запрещены.</li>
 *   <li><b>Сроки:</b> открытие страницы и, отдельно, окончание её запросов — не дольше
 *       {@link BrowserProperties#timeout()} каждое; не дождались окончания запросов — берётся то, что
 *       построено.</li>
 *   <li><b>«Показать ещё»</b> ({@link #expanded}): кнопка с текстом {@link LoadMore#TEXT} нажимается скриптом
 *       страницы, пока после нажатия появляются новые ссылки, — не больше {@link BrowserProperties#expandClicks()} раз
 *       и {@link BrowserProperties#expandBudget()} всего.</li>
 * </ul>
 */
@Component
public class ChromiumPageRenderer implements PageRenderer, DisposableBean {

    /** Типы запросов страницы, которые не нужны для поиска ссылок: не загружаются. */
    private static final Set<String> SKIPPED_RESOURCES = Set.of("image", "font", "media");
    /** Сколько хостов помнится проверенными ({@link AddressPolicy}); больше — память очищается. */
    private static final int MAX_CHECKED_HOSTS = 1000;
    /** Сверх двух сроков страницы — запас ожидания своей очереди к браузеру. */
    private static final long QUEUE_MARGIN_MS = 10_000;
    /** Порог кода ответа: страница с ним — не открылась. */
    private static final int HTTP_ERROR = 400;
    /** Скрипт страницы: нажать видимую кнопку «показать ещё»; {@code true} — нажата. */
    private static final String CLICK_LOAD_MORE = """
            ([selector, pattern]) => {
              const text = new RegExp(pattern);
              const plain = (value) => value.normalize('NFKD').replace(/\\p{M}/gu, '').toLowerCase();
              const button = [...document.querySelectorAll(selector)]
                .find((element) => element.offsetParent !== null && text.test(plain(element.innerText || '')));
              if (!button) {
                return false;
              }
              button.click();
              return true;
            }""";
    /** Скрипт страницы: сколько на ней ссылок. */
    private static final String COUNT_LINKS = "() => document.querySelectorAll('a[href]').length";
    private static final Logger LOG = LoggerFactory.getLogger(ChromiumPageRenderer.class);

    private final BrowserProperties properties;
    private final AddressPolicy addressPolicy;
    private final String userAgent;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "chromium-renderer");
        worker.setDaemon(true);
        return worker;
    });
    /** Хост → разрешён ли (только в потоке браузера). */
    private final Map<String, Boolean> checkedHosts = new HashMap<>();
    private Playwright playwright;
    private Browser browser;

    /**
     * @param properties браузер включён и сроки
     * @param http       разрешены ли внутренние адреса (только локальные заглушки)
     * @param politeness User-Agent ядра
     */
    public ChromiumPageRenderer(BrowserProperties properties, ExternalHttpProperties http,
            PolitenessProperties politeness) {
        this.properties = properties;
        this.addressPolicy = new AddressPolicy(http.allowPrivateAddresses());
        this.userAgent = politeness.userAgent();
    }

    @Override
    public Optional<Document> render(URI uri) {
        return inBrowser(uri, false);
    }

    @Override
    public Optional<Document> expanded(URI uri) {
        return inBrowser(uri, true);
    }

    private Optional<Document> inBrowser(URI uri, boolean expand) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        AtomicBoolean abandoned = new AtomicBoolean();
        Callable<Optional<Document>> task = () -> abandoned.get() ? Optional.<Document>empty() : open(uri, expand);
        Future<Optional<Document>> result = thread.submit(task);
        long wait = 2 * properties.timeout().toMillis() + QUEUE_MARGIN_MS
                + (expand ? properties.expandBudget().toMillis() : 0);
        try {
            return result.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            abandoned.set(true);
            LOG.info("Rendering {} did not finish in time", uri);
            return Optional.empty();
        } catch (InterruptedException exception) {
            abandoned.set(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException exception) {
            LOG.info("Rendering {} failed: {}", uri, String.valueOf(exception.getCause()));
            return Optional.empty();
        }
    }

    /** В потоке браузера: страница в чистом профиле; {@code expand} — нажимать «показать ещё». */
    private Optional<Document> open(URI uri, boolean expand) {
        double timeout = properties.timeout().toMillis();
        AtomicBoolean tainted = new AtomicBoolean();
        try (BrowserContext context = browser().newContext(new Browser.NewContextOptions().setUserAgent(userAgent)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            context.setDefaultTimeout(timeout);
            context.route("**/*", this::filter);
            context.onRequest(request -> {
                if (request.redirectedFrom() != null && !permitted(request.url())) {
                    tainted.set(true);
                }
            });
            Page page = context.newPage();
            page.onWebSocket(socket -> {
                if (!permitted(socket.url())) {
                    tainted.set(true);
                }
            });
            Response response = page.navigate(uri.toString(),
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(timeout));
            if (response == null || response.status() >= HTTP_ERROR) {
                return Optional.empty();
            }
            settle(page, timeout);
            boolean complete = !expand || expand(page, timeout);
            String html = page.content();
            if (tainted.get()) {
                LOG.info("Rendering {}: a redirect or web socket went to a forbidden address, page discarded", uri);
                return Optional.empty();
            }
            Document built = Jsoup.parse(html, page.url());
            if (!complete) {
                built.body().attr(INCOMPLETE, "true");
            }
            return Optional.of(built);
        } catch (PlaywrightException exception) {
            LOG.info("Rendering {} failed: {}", uri, exception.getMessage());
            if (browser != null && !browser.isConnected()) {
                closeBrowser();
            }
            return Optional.empty();
        }
    }

    /** Ждёт окончания запросов страницы; не дождались — берётся построенное. */
    private static void settle(Page page, double timeout) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(timeout));
        } catch (TimeoutError stillLoading) {
            LOG.debug("Rendering {}: requests did not settle, taking what is built", page.url());
        }
    }

    /**
     * «Показать ещё», пока после нажатия прибавляются ссылки (в пределах числа нажатий и общего срока).
     *
     * @return {@code true} — список кончился (кнопки нет или ссылок не прибавилось); {@code false} — упор в предел
     */
    private boolean expand(Page page, double timeout) {
        long until = System.nanoTime() + properties.expandBudget().toNanos();
        for (int click = 0; click < properties.expandClicks() && System.nanoTime() < until; click++) {
            int before = links(page);
            if (!Boolean.TRUE.equals(page.evaluate(CLICK_LOAD_MORE, List.of(LoadMore.SELECTOR, LoadMore.TEXT.pattern())))) {
                return true;
            }
            settle(page, timeout);
            if (links(page) <= before) {
                return true;
            }
        }
        LOG.info("Rendering {}: stopped pressing load more at the limit", page.url());
        return false;
    }

    private static int links(Page page) {
        return ((Number) page.evaluate(COUNT_LINKS)).intValue();
    }

    /** В потоке браузера: пропустить запрос страницы или отказать. */
    private void filter(Route route) {
        String type = route.request().resourceType();
        URI target;
        try {
            target = URI.create(route.request().url());
        } catch (IllegalArgumentException malformed) {
            route.abort();
            return;
        }
        if (SKIPPED_RESOURCES.contains(type) || !permitted(target, Set.of("http", "https"))) {
            route.abort();
            return;
        }
        route.resume();
    }

    /** В потоке браузера: адрес перехода или веб-сокета разрешён. */
    private boolean permitted(String url) {
        try {
            return permitted(URI.create(url), Set.of("http", "https", "ws", "wss"));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private boolean permitted(URI target, Set<String> schemes) {
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        return schemes.contains(scheme) && target.getHost() != null && allowed(target.getHost());
    }

    private boolean allowed(String host) {
        if (checkedHosts.size() > MAX_CHECKED_HOSTS) {
            checkedHosts.clear();
        }
        return checkedHosts.computeIfAbsent(host.toLowerCase(Locale.ROOT), this::resolvesToAllowed);
    }

    private boolean resolvesToAllowed(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                addressPolicy.requireAllowed(address);
            }
            return true;
        } catch (UnknownHostException refused) {
            // В том числе BlockedAddressException — адрес запрещён политикой.
            return false;
        }
    }

    private Browser browser() {
        if (browser == null) {
            // Не запустился браузер — процесс Playwright закрывается сразу, а не остаётся висеть (аудит §244).
            Playwright created = Playwright.create();
            try {
                browser = created.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            } catch (PlaywrightException failed) {
                created.close();
                throw failed;
            }
            playwright = created;
            LOG.info("Chromium started for rendering pages");
        }
        return browser;
    }

    private void closeBrowser() {
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (PlaywrightException exception) {
            LOG.debug("Closing Chromium: {}", exception.getMessage());
        } finally {
            playwright = null;
            browser = null;
        }
    }

    /**
     * Остановка: браузер закрывается в своём потоке.
     *
     * @throws InterruptedException остановка прервана
     */
    @Override
    public void destroy() throws InterruptedException {
        thread.submit(this::closeBrowser);
        thread.shutdown();
        thread.awaitTermination(QUEUE_MARGIN_MS, TimeUnit.MILLISECONDS);
    }
}
