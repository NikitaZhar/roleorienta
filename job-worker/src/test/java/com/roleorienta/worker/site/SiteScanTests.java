package com.roleorienta.worker.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskService;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Скан Common Crawl на заглушке его хранилища (встроенный HTTP-сервер JDK, запросы частей файлов
 * {@code Range}) и настоящей PostgreSQL: выбор блоков зоны {@code .sk} по оглавлению, страница
 * контактов вместо главной, IČO действующей компании — подтверждённый сайт; неизвестный и
 * прекращённый IČO — нет. Одно задание — один блок: скан идёт цепочкой заданий.
 */
@SpringBootTest(properties = {"app.site.blocks-per-task=1", "app.site.request-interval=0ms"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SiteScanTests {

    private static final String CRAWL = "CC-MAIN-TEST";
    private static final String INDEX_DIR = "/cc-index/collections/" + CRAWL + "/indexes/";
    private static final String WARC = "crawl-data/" + CRAWL + "/test.warc.gz";
    private static final int MAX_TASK_ROUNDS = 10;

    /** Файлы заглушки: путь → содержимое. */
    private static final Map<String, byte[]> FILES = new HashMap<>();
    private static final HttpServer STORE = startStore();

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Адреса Common Crawl — заглушка.
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void storeProperties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STORE.getAddress().getPort();
        registry.add("app.site.index-url", () -> base);
        registry.add("app.site.data-url", () -> base);
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopStore() {
        STORE.stop(0);
    }

    /**
     * Чистые таблицы, три компании (одна прекращена) и обход: оглавление из трёх блоков — перед
     * зоной (кончается адресом зоны), в зоне, после зоны.
     *
     * @throws IOException не бросается
     */
    @BeforeEach
    void setUp() throws IOException {
        for (String table : new String[] {"company_site", "cc_index_block", "company", "outbox_event", "task"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("""
                INSERT INTO company (country, registration_number, name, registry, terminated_on) VALUES
                ('SK', '11111111', 'Alfa s.r.o.', 'RPO', NULL), ('SK', '33333333', 'Mesto', 'RPO', NULL),
                ('SK', '44444444', 'Stará s.r.o.', 'RPO', DATE '2020-01-01')
                """);
        ByteArrayOutputStream warc = new ByteArrayOutputStream();
        String aaa = page(warc, "IČO: 33 333 333");
        String alfaRoot = page(warc, "Vitajte");
        String alfaContact = page(warc, "Alfa s.r.o., IČO: 11 111 111");
        String beta = page(warc, "IČO: 99999999");
        String gamma = page(warc, "IČO: 44 444 444");
        FILES.put("/" + WARC, warc.toByteArray());

        byte[] block0 = gzip(cdx("se,firma)/", "http://firma.se/", "200", "text/html", alfaRoot)
                + cdx("sk,aaa)/", "http://aaa.sk/", "200", "text/html", aaa));
        byte[] block1 = gzip(cdx("sk,alfa)/", "http://alfa.sk/", "200", "text/html", alfaRoot)
                + cdx("sk,alfa)/kontakt", "http://alfa.sk/kontakt", "200", "text/html", alfaContact)
                + cdx("sk,alfa)/logo.png", "http://alfa.sk/logo.png", "200", "image/png", alfaRoot)
                + cdx("sk,beta)/", "http://beta.sk/", "200", "text/html", beta)
                + cdx("sk,gamma)/o-nas", "https://gamma.sk/o-nas", "200", "text/html", gamma)
                + cdx("sk,delta)/kontakt", "http://delta.sk/kontakt", "404", "text/html", alfaContact));
        byte[] block2 = gzip(cdx("uk,firma)/", "http://firma.uk/", "200", "text/html", aaa));
        FILES.put(INDEX_DIR + "cdx-00000.gz", concat(block0, block1, block2));
        FILES.put(INDEX_DIR + "cluster.idx", String.join("\n",
                "se,firma)/ 20260901000000\tcdx-00000.gz\t0\t" + block0.length + "\t1",
                "sk,alfa)/ 20260901000000\tcdx-00000.gz\t" + block0.length + "\t" + block1.length + "\t2",
                "uk,firma)/ 20260901000000\tcdx-00000.gz\t" + (block0.length + block1.length) + "\t"
                        + block2.length + "\t3").getBytes(StandardCharsets.UTF_8));
        FILES.put("/collinfo.json", ("[{\"id\":\"" + CRAWL + "\"}]").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Сайты: {@code aaa.sk} (адрес зоны в конце блока перед зоной) и {@code alfa.sk} по странице
     * контактов; {@code beta.sk} — IČO неизвестен, {@code gamma.sk} — компания прекращена, 404 и не
     * HTML — не читаются. Блоки просмотрены, повторный скан ничего не добавляет.
     */
    @Test
    void findsSitesByIcoOnContactPages() {
        runScan("first");

        assertThat(jdbcTemplate.queryForList("""
                SELECT c.registration_number || ':' || s.host || ':' || s.evidence_url
                FROM company_site s JOIN company c ON c.id = s.company_id ORDER BY 1
                """, String.class)).containsExactly("11111111:alfa.sk:http://alfa.sk/kontakt",
                "33333333:aaa.sk:http://aaa.sk/");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM cc_index_block WHERE NOT done", Integer.class))
                .isZero();

        runScan("second");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_site", Integer.class)).isEqualTo(2);
    }

    private void runScan(String key) {
        taskService.enqueue(SiteScanHandler.TYPE, SiteScanHandler.taskKey(key), SiteScanHandler.payload());
        for (int round = 0; round < MAX_TASK_ROUNDS; round++) {
            List<Long> queued = jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id",
                    Long.class);
            if (queued.isEmpty()) {
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE state <> 'DONE'",
                        Integer.class)).isZero();
                return;
            }
            queued.forEach(executor::execute);
        }
        throw new AssertionError("Scan did not finish in " + MAX_TASK_ROUNDS + " rounds");
    }

    /**
     * Дописывает в архив запись WARC со страницей; возвращает «смещение длина» записи.
     */
    private static String page(ByteArrayOutputStream warc, String text) throws IOException {
        byte[] record = gzip("WARC/1.0\r\nWARC-Type: response\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n"
                + "<html><body><footer>" + text + "</footer></body></html>");
        int offset = warc.size();
        warc.write(record);
        return offset + " " + record.length;
    }

    private static String cdx(String key, String url, String status, String mime, String place) {
        String[] offsetAndLength = place.split(" ");
        return key + " 20260901000000 {\"url\": \"" + url + "\", \"mime\": \"" + mime + "\", \"status\": \"" + status
                + "\", \"filename\": \"" + WARC + "\", \"offset\": \"" + offsetAndLength[0] + "\", \"length\": \""
                + offsetAndLength[1] + "\"}\n";
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Arrays.stream(parts).forEach(bytes::writeBytes);
        return bytes.toByteArray();
    }

    /**
     * Заглушка хранилища: файл целиком или его часть по {@code Range: bytes=a-b} (206).
     */
    private static HttpServer startStore() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                byte[] file = FILES.get(exchange.getRequestURI().getPath());
                if (file == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                String range = exchange.getRequestHeaders().getFirst("Range");
                byte[] body = file;
                int status = 200;
                if (range != null) {
                    String[] bounds = range.substring("bytes=".length()).split("-");
                    body = Arrays.copyOfRange(file, Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]) + 1);
                    status = 206;
                }
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
