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
import java.util.List;
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
 * индекса — сотни мегабайт. Нагрузка ограничена паузой перед каждым запросом. 429 и 5xx
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
        try (InputStream body = get(URI.create(properties.indexUrl() + "/collinfo.json"), null)) {
            JsonNode crawls = JSON.readTree(body);
            if (!crawls.isArray() || crawls.isEmpty()) {
                throw new IOException("Empty Common Crawl collinfo.json");
            }
            return crawls.get(0).path("id").asText();
        }
    }

    /**
     * Строки оглавления индекса обхода, пока {@code keepReading} их принимает (оглавление
     * отсортировано — чтение обрывается, как только нужный диапазон пройден).
     *
     * @param crawl       обход
     * @param keepReading получает строку; {@code false} — дальше не читать
     * @throws IOException оглавление не получено
     */
    public void clusterIndex(String crawl, Predicate<String> keepReading) throws IOException {
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
        try (InputStream body = get(uri, "bytes=" + offset + "-" + (offset + length - 1))) {
            return body.readAllBytes();
        }
    }

    private InputStream get(URI uri, String range) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(properties.timeout()).GET();
        if (range != null) {
            request.header("Range", range);
        }
        try {
            Thread.sleep(properties.requestInterval());
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < STATUS_OK_MIN || status > STATUS_OK_MAX) {
                response.body().close();
                String message = "Common Crawl " + uri + ": HTTP " + status;
                throw status == STATUS_TOO_MANY_REQUESTS || status >= STATUS_SERVER_ERROR_MIN
                        ? new UnavailableException(message) : new IOException(message);
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted: " + uri);
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
