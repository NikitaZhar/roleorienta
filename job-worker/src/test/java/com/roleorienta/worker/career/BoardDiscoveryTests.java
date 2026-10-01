package com.roleorienta.worker.career;

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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Обратный путь на заглушке (встроенный HTTP-сервер JDK: хранилище Common Crawl и Greenhouse Job
 * Board API) и настоящей PostgreSQL: доски Greenhouse из адресов индекса, проверка на вакансии в
 * Словакии, подключение словацкой доски как источника без связи с компанией.
 */
@SpringBootTest(properties = {"app.site.request-interval=0ms", "app.http.allow-private-addresses=true"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BoardDiscoveryTests {

    private static final String CRAWL = "CC-MAIN-TEST";
    private static final String INDEX_DIR = "/cc-index/collections/" + CRAWL + "/indexes/";
    private static final int MAX_TASK_ROUNDS = 10;
    private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();
    private static final HttpServer STORE = startStore();

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BoardDiscoveryRepository repository;

    /**
     * Common Crawl и Greenhouse — заглушка.
     *
     * @param registry свойства тестового контекста
     */
    @DynamicPropertySource
    static void storeProperties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STORE.getAddress().getPort();
        registry.add("app.site.index-url", () -> base);
        registry.add("app.site.data-url", () -> base);
        registry.add("app.adapter.greenhouse.base-url", () -> base);
    }

    /**
     * Остановка заглушки.
     */
    @AfterAll
    static void stopStore() {
        STORE.stop(0);
    }

    /**
     * Чистые таблицы; индекс из блока до досок и блока с адресами Greenhouse; доска {@code acme} —
     * вакансия в Братиславе, {@code beta} — в Берлине.
     *
     * @throws IOException не бросается
     */
    @BeforeEach
    void setUp() throws IOException {
        for (String table : new String[] {"discovered_board", "cc_index_block", "company_source", "source",
                "outbox_event", "task"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO collection_country (country, active) VALUES ('SK', TRUE) "
                + "ON CONFLICT (country) DO UPDATE SET active = TRUE");
        byte[] block0 = gzip(cdx("com,example)/", "https://example.com/"));
        byte[] block1 = gzip(cdx("io,greenhouse,boards)/acme/jobs/1", "https://boards.greenhouse.io/acme/jobs/1")
                + cdx("io,greenhouse,boards)/beta", "https://boards.greenhouse.io/beta")
                + cdx("io,greenhouse,boards)/robots.txt", "https://boards.greenhouse.io/robots.txt"));
        FILES.put(INDEX_DIR + "cdx-00000.gz", concat(block0, block1));
        FILES.put(INDEX_DIR + "cluster.idx", String.join("\n",
                "com,example)/ 20260901000000\tcdx-00000.gz\t0\t" + block0.length + "\t1",
                "io,greenhouse,boards)/acme/jobs/1 20260901000000\tcdx-00000.gz\t" + block0.length + "\t"
                        + block1.length + "\t2",
                "uk,firma)/ 20260901000000\tcdx-00000.gz\t0\t1\t3").getBytes(StandardCharsets.UTF_8));
        FILES.put("/collinfo.json", ("[{\"id\":\"" + CRAWL + "\"}]").getBytes(StandardCharsets.UTF_8));
        FILES.put("/v1/boards/acme/jobs", jobs("Bratislava, Slovakia"));
        FILES.put("/v1/boards/beta/jobs", jobs("Berlin, Germany"));
    }

    /**
     * Проверка очередью: провайдеры чередуются — Workday не ждёт, пока проверятся все доски
     * Greenhouse.
     */
    @Test
    void checksProvidersInTurn() {
        jdbcTemplate.update("""
                INSERT INTO discovered_board (provider, board, crawl) VALUES
                ('greenhouse', 'a', 'c'), ('greenhouse', 'b', 'c'), ('greenhouse', 'c', 'c'),
                ('workday', 'x.wd1.myworkdayjobs.com/external', 'c')
                """);

        assertThat(repository.boardsToCheck(2, Duration.ofDays(30))).containsExactly(
                new Board("greenhouse", "a"), new Board("workday", "x.wd1.myworkdayjobs.com/external"));
    }

    /**
     * Доски {@code acme} и {@code beta} найдены; словацкая {@code acme} подключена (страна SK), связи с
     * компанией нет; {@code beta} проверена и не подключена.
     */
    @Test
    void connectsBoardsWithSlovakPostings() {
        taskService.enqueue(BoardDiscoveryHandler.TYPE, BoardDiscoveryHandler.taskKey("test"),
                BoardDiscoveryHandler.payload());
        for (int round = 0; round < MAX_TASK_ROUNDS; round++) {
            List<Long> queued = jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id",
                    Long.class);
            if (queued.isEmpty()) {
                break;
            }
            queued.forEach(executor::execute);
        }

        assertThat(jdbcTemplate.queryForList(
                "SELECT board || ':' || coalesce(country, '-') FROM discovered_board ORDER BY board", String.class))
                .containsExactly("acme:SK", "beta:-");
        assertThat(jdbcTemplate.queryForList("SELECT provider || ':' || board || ':' || country FROM source",
                String.class)).containsExactly("greenhouse:acme:SK");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_source", Integer.class)).isZero();
    }

    private static byte[] jobs(String location) {
        return ("{\"jobs\":[{\"id\":1,\"title\":\"Java Developer\",\"absolute_url\":\"https://example.com/1\","
                + "\"location\":{\"name\":\"" + location + "\"}}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String cdx(String key, String url) {
        return key + " 20260901000000 {\"url\": \"" + url + "\", \"status\": \"200\"}\n";
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
     * Заглушка: файл целиком или его часть по {@code Range: bytes=a-b} (206); прочее — 404.
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
