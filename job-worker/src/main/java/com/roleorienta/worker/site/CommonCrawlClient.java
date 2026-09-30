package com.roleorienta.worker.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;

/**
 * Доступ к открытым данным Common Crawl (https://commoncrawl.org/get-started): список обходов,
 * индекс адресов и архивы страниц. Индекс обхода — файлы {@code cdx-NNNNN.gz} из сжатых блоков
 * (~3000 строк «адрес → место страницы в архиве»), оглавление блоков — {@code cluster.idx}; блок и
 * страница читаются запросом части файла ({@code Range}), без скачивания файлов целиком.
 * https://index.commoncrawl.org/
 *
 * <p>Это официальный набор открытых данных, а не сайт, поэтому свой клиент (JDK
 * {@link HttpClient}), а не {@code ExternalHttpClient}: адреса фиксированы настройками, оглавление
 * индекса — сотни мегабайт. Нагрузка ограничена паузой перед каждым запросом. Список обходов, блок
 * индекса и страница читаются целиком с общим сроком на запрос и тело ({@code timeout}): таймаут
 * запроса JDK покрывает только ожидание заголовков, зависшее тело держало бы обработчик заданий
 * бесконечно. Оглавление (раз в месяц, сотни мегабайт) читается потоком. 429 и 5xx
 * («замедлитесь», сбой) — {@link UnavailableException}: задание повторится позже; прочие ответы
 * не 2xx — {@link IOException}.</p>
 */
@Component
public class CommonCrawlClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int STATUS_OK_MIN = 200;
    private static final int STATUS_OK_MAX = 299;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int STATUS_SERVER_ERROR_MIN = 500;

    private final HttpClient http;
    private final CommonCrawlProperties properties;

    /**
     * @param properties адреса, пауза и таймаут
     */
    public CommonCrawlClient(CommonCrawlProperties properties) {
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.properties = properties;
    }

    /**
     * @return id последнего обхода, например {@code CC-MAIN-2026-39}
     * @throws IOException список обходов не получен
     */
    public String latestCrawl() throws IOException {
        JsonNode crawls = JSON.readTree(bytes(URI.create(properties.indexUrl() + "/collinfo.json"), null));
        if (!crawls.isArray() || crawls.isEmpty()) {
            throw new IOException("Empty Common Crawl collinfo.json");
        }
        return crawls.get(0).path("id").asText();
    }

    /**
     * Блоки индекса, где могут быть адреса с данными префиксами SURT (хост задом наперёд:
     * {@code sk,firma)/kontakt}): каждый блок, начинающийся с префикса, и блок перед первым таким
     * (он может заканчиваться адресами префикса). Оглавление читается один раз и обрывается за
     * последним префиксом. Строка оглавления:
     * {@code <ключ> <время>\t<файл>\t<смещение>\t<длина>\t<№>}.
     *
     * @param crawl    обход
     * @param prefixes префиксы SURT, по возрастанию
     * @return блоки по порядку, номера с 0
     * @throws IOException оглавление не получено
     */
    public List<IndexBlock> blocks(String crawl, List<String> prefixes) throws IOException {
        List<IndexBlock> blocks = new ArrayList<>();
        String last = prefixes.get(prefixes.size() - 1);
        String[][] previous = new String[1][];
        clusterIndex(crawl, line -> {
            String[] parts = line.split("\t");
            String key = parts[0];
            for (String prefix : prefixes) {
                boolean entered = previous[0] != null && previous[0][0].compareTo(prefix) < 0
                        && key.compareTo(prefix) >= 0;
                if (entered && !previous[0][0].startsWith(prefix)) {
                    add(blocks, previous[0]);
                }
                if (key.startsWith(prefix)) {
                    add(blocks, parts);
                }
            }
            previous[0] = parts;
            return key.compareTo(last) <= 0 || key.startsWith(last);
        });
        return blocks;
    }

    private static void add(List<IndexBlock> blocks, String[] parts) {
        long offset = Long.parseLong(parts[2]);
        boolean known = blocks.stream().anyMatch(block -> block.file().equals(parts[1]) && block.offset() == offset);
        if (!known) {
            blocks.add(new IndexBlock(blocks.size(), parts[1], offset, Integer.parseInt(parts[3])));
        }
    }

    private void clusterIndex(String crawl, Predicate<String> keepReading) throws IOException {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                get(URI.create(indexDir(crawl) + "cluster.idx"), null), StandardCharsets.UTF_8))) {
            String line = lines.readLine();
            while (line != null && keepReading.test(line)) {
                line = lines.readLine();
            }
        }
    }

    /**
     * @param crawl обход
     * @param block блок индекса
     * @return строки блока: {@code <ключ SURT> <время> <JSON>}
     * @throws IOException блок не получен
     */
    public List<String> block(String crawl, IndexBlock block) throws IOException {
        byte[] bytes = range(URI.create(indexDir(crawl) + block.file()), block.offset(), block.length());
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(bytes)), StandardCharsets.UTF_8))) {
            return lines.lines().toList();
        }
    }

    /**
     * @param page страница в архиве
     * @return запись архива (WARC), распакованная: заголовки WARC, заголовки HTTP, тело
     * @throws IOException запись не получена
     */
    public byte[] record(PageRef page) throws IOException {
        byte[] bytes = range(URI.create(properties.dataUrl() + "/" + page.filename()), page.offset(), page.length());
        try (InputStream record = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return record.readAllBytes();
        }
    }

    private String indexDir(String crawl) {
        return properties.dataUrl() + "/cc-index/collections/" + crawl + "/indexes/";
    }

    private byte[] range(URI uri, long offset, int length) throws IOException {
        return bytes(uri, "bytes=" + offset + "-" + (offset + length - 1));
    }

    /**
     * Ответ целиком; запрос и тело — не дольше {@code timeout}, иначе запрос отменяется и
     * {@link UnavailableException}.
     */
    private byte[] bytes(URI uri, String range) throws IOException {
        pause(uri);
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request(uri, range),
                HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> response = pending.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            check(uri, response.statusCode());
            return response.body();
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new UnavailableException("Timeout: " + uri);
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof IOException cause ? cause : new IOException(failed.getCause());
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted: " + uri);
        }
    }

    /**
     * Ответ потоком (оглавление индекса): таймаут — до заголовков ответа.
     */
    private InputStream get(URI uri, String range) throws IOException {
        pause(uri);
        try {
            HttpResponse<InputStream> response = http.send(request(uri, range),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < STATUS_OK_MIN || response.statusCode() > STATUS_OK_MAX) {
                response.body().close();
            }
            check(uri, response.statusCode());
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted: " + uri);
        }
    }

    private HttpRequest request(URI uri, String range) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(properties.timeout()).GET();
        if (range != null) {
            request.header("Range", range);
        }
        return request.build();
    }

    private void pause(URI uri) throws UnavailableException {
        try {
            Thread.sleep(properties.requestInterval());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted: " + uri);
        }
    }

    /**
     * Не 2xx: 429 и 5xx — {@link UnavailableException}, прочее — {@link IOException}.
     */
    private static void check(URI uri, int status) throws IOException {
        if (status < STATUS_OK_MIN || status > STATUS_OK_MAX) {
            String message = "Common Crawl " + uri + ": HTTP " + status;
            throw status == STATUS_TOO_MANY_REQUESTS || status >= STATUS_SERVER_ERROR_MIN
                    ? new UnavailableException(message) : new IOException(message);
        }
    }

    /**
     * Common Crawl временно не отвечает (429, 5xx, прерывание): повторить позже, а не пропускать.
     */
    public static class UnavailableException extends IOException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message причина
         */
        public UnavailableException(String message) {
            super(message);
        }
    }
}
