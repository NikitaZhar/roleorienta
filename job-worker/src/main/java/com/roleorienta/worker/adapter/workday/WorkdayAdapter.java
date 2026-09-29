package com.roleorienta.worker.adapter.workday;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер Workday: список вакансий витрины
 * {@code POST https://<тенант>.<dc>.myworkdayjobs.com/wday/cxs/<тенант>/<сайт>/jobs} страницами
 * {@code offset/limit}. Поля публикации — {@code title}, {@code externalPath} (внешний id и ссылка),
 * {@code locationsText}; {@code total} приходит только на первой странице. Текста в списке нет —
 * он читается отдельно ({@link #content}).
 *
 * <p>Доска — {@code <тенант>.<dc>.myworkdayjobs.com/<сайт>}; другой хост не читается (отказ
 * {@code BLOCKED}). Отказ на первой странице — источник недоступен; на следующих, пустая страница
 * раньше {@code total} или упор в потолок страниц — неполное чтение (вакансии не закрываются).
 * Endpoint не документирован как публичный контракт (технический документ §5).</p>
 */
@Component
public class WorkdayAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "workday";

    private static final Pattern BOARD = Pattern.compile(
            "(([a-z0-9-]+)\\.wd\\d+\\.myworkdayjobs\\.com)/([A-Za-z0-9_-]+)");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(WorkdayAdapter.class);

    private final ExternalHttpClient httpClient;
    private final WorkdayProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес витрины и размер страниц
     */
    public WorkdayAdapter(ExternalHttpClient httpClient, WorkdayProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SourceReadResult read(String board) {
        Matcher matcher = BOARD.matcher(board);
        if (!matcher.matches()) {
            return new SourceReadResult.Unavailable(new HttpResult.PermanentFailure(
                    HttpResult.Kind.BLOCKED, "Not a Workday board: " + board));
        }
        String base = properties.baseUrlTemplate().replace("{host}", matcher.group(1));
        String site = matcher.group(3);
        URI uri = URI.create(apiBase(matcher) + "/jobs");
        List<FetchedPosting> postings = new ArrayList<>();
        int total = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            int offset = page * properties.pageSize();
            HttpResult result = httpClient.postJson(uri, requestBody(offset));
            JsonNode json = result instanceof HttpResult.Success success ? readJson(success.body()) : null;
            if (json == null) {
                HttpResult failure = result instanceof HttpResult.Success
                        ? new HttpResult.TemporaryFailure("Malformed Workday response", Duration.ZERO) : result;
                if (page == 0) {
                    return new SourceReadResult.Unavailable(failure);
                }
                LOG.warn("Workday board {} read partially at offset {}: {}", board, offset, failure);
                return SourceReadResult.Read.partial(postings, PartialReason.PAGE_FAILED);
            }
            if (page == 0) {
                total = json.path("total").asInt();
            }
            JsonNode jobs = json.path("jobPostings");
            for (JsonNode job : jobs) {
                String path = job.path("externalPath").asText();
                postings.add(new FetchedPosting(path, job.path("title").asText(), base + "/" + site + path,
                        job.path("locationsText").isTextual() ? job.path("locationsText").asText() : null, null));
            }
            if (offset + jobs.size() >= total) {
                return SourceReadResult.Read.full(postings);
            }
            if (jobs.isEmpty()) {
                return SourceReadResult.Read.partial(postings, PartialReason.LIST_ENDED_EARLY);
            }
        }
        LOG.warn("Workday board {} exceeds {} pages, read partially", board, properties.maxPages());
        return SourceReadResult.Read.partial(postings, PartialReason.PAGE_LIMIT);
    }

    /**
     * Текст публикации: {@code GET <api>/<externalPath>}, поле {@code jobPostingInfo.jobDescription}.
     */
    @Override
    public String content(String board, String externalId) {
        Matcher matcher = BOARD.matcher(board);
        if (!matcher.matches() || !externalId.startsWith("/job/")) {
            return null;
        }
        HttpResult result;
        try {
            result = httpClient.get(URI.create(apiBase(matcher) + externalId));
        } catch (IllegalArgumentException malformedPath) {
            return null;
        }
        if (!(result instanceof HttpResult.Success success)) {
            LOG.warn("Workday posting {} on {} not read: {}", externalId, board, result);
            return null;
        }
        try {
            JsonNode description = JSON.readTree(success.body()).path("jobPostingInfo").path("jobDescription");
            if (!description.isTextual()) {
                LOG.warn("Workday posting {} on {} has no jobDescription", externalId, board);
                return null;
            }
            return description.asText();
        } catch (JsonProcessingException exception) {
            LOG.warn("Workday posting {} on {} is not JSON: {}", externalId, board, exception.getOriginalMessage());
            return null;
        }
    }

    private String apiBase(Matcher board) {
        return properties.baseUrlTemplate().replace("{host}", board.group(1))
                + "/wday/cxs/" + board.group(2) + "/" + board.group(3);
    }

    private String requestBody(int offset) {
        return "{\"appliedFacets\":{},\"limit\":" + properties.pageSize() + ",\"offset\":" + offset
                + ",\"searchText\":\"\"}";
    }

    private static JsonNode readJson(String body) {
        try {
            JsonNode json = JSON.readTree(body);
            return json != null && json.path("jobPostings").isArray() ? json : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
