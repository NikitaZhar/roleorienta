package com.roleorienta.worker.site;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code SITE_SCAN}: карта «IČO → сайт» из последнего обхода Common Crawl (технический
 * документ §5.1, подэтап 1.7; решение владельца, §25).
 *
 * <ol>
 *   <li>Из оглавления индекса выбираются блоки зоны {@code .sk} (адреса отсортированы в виде SURT —
 *       хост задом наперёд: {@code sk,firma)/kontakt}) и записываются в БД.</li>
 *   <li>Из блока берётся по одной странице на хост: страница контактов или реквизитов
 *       ({@code kontakt}, {@code o-nas}, {@code impressum} …), а если её нет — главная.</li>
 *   <li>Страница читается из архива, в тексте ищутся IČO ({@link IcoExtractor}); IČO действующей
 *       компании из реестра — подтверждённый сайт этой компании. Больше трёх разных IČO на
 *       странице — каталог чужих организаций, страница ничего не подтверждает.</li>
 *   <li>Находки блока и отметка «просмотрен» — одна транзакция; за задание не больше
 *       {@link CommonCrawlProperties#blocksPerTask()} блоков, дальше — следующее задание.</li>
 * </ol>
 *
 * <p>Common Crawl временно не отвечает — повтор задания с того же блока. Одна страница не читается
 * (нет в архиве, испорчена) — пропускается.</p>
 */
@Component
public class SiteScanHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "SITE_SCAN";

    private static final String ZONE = "sk,";
    private static final String PAYLOAD = "{}";
    private static final List<String> CONTACT_PAGES = List.of(
            "kontakt", "contact", "o-nas", "onas", "o_nas", "about", "impressum", "podmienky", "firma");
    private static final int MAX_URL = 2000;
    /** Больше IČO на странице — каталог чужих организаций (портал, реестр НКО), не сайт компании. */
    private static final int MAX_NUMBERS_PER_PAGE = 3;
    private static final byte[] HEADERS_END = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(SiteScanHandler.class);

    private final CommonCrawlClient client;
    private final SiteScanRepository repository;
    private final TaskService taskService;
    private final CommonCrawlProperties properties;

    /**
     * @param client      Common Crawl
     * @param repository  блоки и сайты
     * @param taskService постановка следующего задания
     * @param properties  блоков за задание
     */
    public SiteScanHandler(CommonCrawlClient client, SiteScanRepository repository, TaskService taskService,
            CommonCrawlProperties properties) {
        this.client = client;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * @param suffix часть ключа: дата постановки или место скана
     * @return ключ задания
     */
    public static String taskKey(String suffix) {
        return "site-scan:" + suffix;
    }

    /**
     * @return параметры задания (не нужны)
     */
    public static String payload() {
        return PAYLOAD;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        try {
            String crawl = client.latestCrawl();
            if (!repository.hasBlocks(crawl)) {
                List<IndexBlock> blocks = client.blocks(crawl, List.of(ZONE));
                repository.insertBlocks(crawl, blocks);
                LOG.info("Common Crawl {}: {} index blocks of zone {}", crawl, blocks.size(), ZONE);
            }
            List<IndexBlock> blocks = repository.nextBlocks(crawl, properties.blocksPerTask());
            for (IndexBlock block : blocks) {
                scan(crawl, block);
            }
            if (blocks.size() == properties.blocksPerTask()) {
                taskService.enqueue(TYPE, taskKey(crawl + ":" + blocks.get(blocks.size() - 1).seq()), PAYLOAD);
            }
            return new TaskOutcome.Done();
        } catch (IOException exception) {
            return new TaskOutcome.Retry("Common Crawl not read: " + exception.getMessage(), Duration.ZERO);
        }
    }

    private void scan(String crawl, IndexBlock block) throws IOException {
        Map<PageRef, Set<String>> numbersByPage = new LinkedHashMap<>();
        for (PageRef page : candidates(client.block(crawl, block))) {
            Set<String> numbers;
            try {
                numbers = IcoExtractor.extract(text(client.record(page), page.url()));
            } catch (CommonCrawlClient.UnavailableException unavailable) {
                throw unavailable;
            } catch (IOException unreadable) {
                LOG.debug("Page {} skipped: {}", page.url(), unreadable.getMessage());
                continue;
            }
            if (!numbers.isEmpty() && numbers.size() <= MAX_NUMBERS_PER_PAGE) {
                numbersByPage.put(page, numbers);
            }
        }
        Set<String> allNumbers = new HashSet<>();
        numbersByPage.values().forEach(allNumbers::addAll);
        Map<String, Long> companies = repository.activeCompanies(allNumbers);
        List<SiteScanRepository.SiteMatch> matches = new ArrayList<>();
        numbersByPage.forEach((page, numbers) -> numbers.stream().map(companies::get).filter(Objects::nonNull)
                .distinct().forEach(companyId -> matches.add(
                        new SiteScanRepository.SiteMatch(companyId, page.host(), limit(page.url())))));
        repository.completeBlock(crawl, block.seq(), matches);
        LOG.info("Common Crawl {} block {}: {} sites", crawl, block.seq(), matches.size());
    }

    /**
     * По одной странице на хост: контакты/реквизиты, иначе главная; только HTML с ответом 200.
     */
    private static List<PageRef> candidates(List<String> lines) {
        Map<String, PageRef> byHost = new LinkedHashMap<>();
        for (String line : lines) {
            JsonNode entry = entry(line);
            if (entry == null || !"200".equals(entry.path("status").asText())
                    || !entry.path("mime").asText().contains("html")) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(entry.path("url").asText());
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            boolean contact = isContactPage(path);
            if (uri.getHost() == null || !contact && !path.isEmpty() && !"/".equals(path)) {
                continue;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            PageRef known = byHost.get(host);
            if (known == null || contact && !isContactPage(URI.create(known.url()).getPath())) {
                byHost.put(host, new PageRef(host, uri.toString(), entry.path("filename").asText(),
                        entry.path("offset").asLong(), entry.path("length").asInt()));
            }
        }
        return new ArrayList<>(byHost.values());
    }

    private static JsonNode entry(String line) {
        int json = line.indexOf('{');
        try {
            return json < 0 ? null : JSON.readTree(line.substring(json));
        } catch (JsonProcessingException malformed) {
            return null;
        }
    }

    private static boolean isContactPage(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return CONTACT_PAGES.stream().anyMatch(lower::contains);
    }

    /**
     * Текст страницы из записи архива: после заголовков WARC и заголовков HTTP — тело; сжатое gzip
     * распаковывается; кодировку определяет Jsoup (заголовок {@code meta} или UTF-8).
     */
    private static String text(byte[] record, String url) throws IOException {
        int warcEnd = indexOf(record, HEADERS_END, 0);
        int httpEnd = warcEnd < 0 ? -1 : indexOf(record, HEADERS_END, warcEnd + HEADERS_END.length);
        if (httpEnd < 0) {
            return "";
        }
        String headers = new String(record, warcEnd, httpEnd - warcEnd, StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        InputStream body = new ByteArrayInputStream(Arrays.copyOfRange(record, httpEnd + HEADERS_END.length,
                record.length));
        if (headers.contains("content-encoding: gzip")) {
            body = new GZIPInputStream(body);
        }
        return Jsoup.parse(body, null, url).text();
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        for (int index = from; index <= data.length - pattern.length; index++) {
            if (Arrays.equals(data, index, index + pattern.length, pattern, 0, pattern.length)) {
                return index;
            }
        }
        return -1;
    }

    private static String limit(String url) {
        return url.length() <= MAX_URL ? url : url.substring(0, MAX_URL);
    }
}
