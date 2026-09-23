package com.roleorienta.worker.adapters.workday;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.http.SourceHttpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Определяет {@code site} тенанта Workday по имени хоста — недостающее звено между
 * CT-перечислением ({@code CertSpotterClient}, §52) и кандидатом обнаружения (A1b).
 *
 * <p><b>Зачем.</b> Из Certificate Transparency известен только хост
 * {@code <tenant>.wd<N>.myworkdayjobs.com}, а лента Workday адресуется парой
 * {@code tenant/site} ({@code /wday/cxs/<tenant>/<site>/jobs}); {@code site} в сертификате
 * не виден.</p>
 *
 * <p><b>Почему не redirect-проб.</b> Живая проверка 2026-09-23 (протокол Этапа 0 §3):
 * корень хоста тенанта отвечает {@code 406} на любой GET/HEAD (любой User-Agent и
 * {@code Accept}) и никуда не редиректит, а {@code /<site>} и {@code /<locale>/<site>}
 * отвечают {@code 200}. Узнать {@code site} из корня нельзя.</p>
 *
 * <p><b>Как.</b> Хост сверяется с вендорным шаблоном (без сети); затем по очереди
 * проверяются кандидаты имени сайта из конфигурации
 * ({@code app.discovery.workday.site-candidates}; плейсхолдеры {@code {tenant}} — тенант
 * как в хосте, {@code {Tenant}} — с заглавной буквы) — каждым запросом к самой cxs-ленте:
 * {@code POST <base>/wday/cxs/<tenant>/<site>/jobs} с {@code limit=1}. Сайт признан, если
 * ответ — JSON с числовым {@code total} и массивом {@code jobPostings} (формат §50). Первый
 * признанный кандидат — результат. Число запросов на хост ограничено
 * {@code app.discovery.workday.max-site-probes} (бюджет, A29). Все запросы — через единый
 * {@link SourceHttpClient} (SSRF-контур, A13).</p>
 *
 * <p><b>Поведение cxs (живая проверка 2026-09-23).</b> Имя сайта <b>не чувствительно к
 * регистру</b> ({@code Workday} и {@code workday} дают одну ленту), поэтому кандидаты
 * дедуплицируются без учёта регистра (первое написание сохраняется). Несуществующий
 * сайт — {@code 404} с JSON {@code {"errorCode":"S21", "message":"not found:
 * Job_Posting_Site_ID=..."}}: это штатное «не этот сайт».</p>
 *
 * <p><b>Честные границы.</b> Это проверка по словарю: тенант с нетиповым именем сайта
 * не определится — возвращается пусто, ничего не выдумывается. Резолвер не проверяет
 * непустоту ленты и принадлежность работодателю — это гейт уверенности и A2. Ответ
 * 4xx (кроме 429) или не-JSON на кандидате — «не этот сайт», идём дальше. Сбой
 * источника — 429, 5xx, тайм-аут, отказ соединения — и SSRF-отказ <b>пробрасываются</b>:
 * иначе временная недоступность тенанта выглядела бы как «сайта нет» и кандидат был бы
 * потерян. Решение о повторе — за вызывающим.</p>
 */
@Component
public class WorkdaySiteResolver {

    /** Вендорный шаблон хоста тенанта: {@code <tenant>.wd<N>.myworkdayjobs.com}. */
    static final Pattern TENANT_HOST =
            Pattern.compile("^([a-z0-9][a-z0-9_-]*)\\.wd\\d+\\.myworkdayjobs\\.com$");

    /** Допустимое имя сайта после подстановки (защита пути cxs от мусора). */
    private static final Pattern SITE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]*$");

    /** Минимальное тело списка: одна вакансия — достаточно, чтобы распознать формат. */
    private static final String PROBE_BODY =
            "{\"appliedFacets\":{},\"limit\":1,\"offset\":0,\"searchText\":\"\"}";

    private static final Logger log = LoggerFactory.getLogger(WorkdaySiteResolver.class);

    private final SourceHttpClient httpClient;
    private final List<String> siteCandidates;
    private final int maxSiteProbes;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param httpClient     единый HTTP-клиент (SSRF-контур §9, A13)
     * @param siteCandidates шаблоны имени сайта в порядке проверки (с плейсхолдерами)
     * @param maxSiteProbes  потолок числа запросов на один хост (бюджет, A29)
     */
    public WorkdaySiteResolver(
            SourceHttpClient httpClient,
            @Value("${app.discovery.workday.site-candidates:{Tenant},External,Careers,{Tenant}_Careers,{Tenant}_External}")
                    List<String> siteCandidates,
            @Value("${app.discovery.workday.max-site-probes:5}") int maxSiteProbes) {
        this.httpClient = httpClient;
        this.siteCandidates = List.copyOf(siteCandidates);
        this.maxSiteProbes = maxSiteProbes;
    }

    /**
     * Определяет доску Workday по хосту тенанта из CT.
     *
     * @param host имя хоста (напр. {@code acme.wd5.myworkdayjobs.com})
     * @return доска с {@code tenant/site}; пусто, если хост не по шаблону Workday или
     *         ни один кандидат сайта не подтвердился лентой
     */
    public Optional<WorkdayBoard> resolve(String host) {
        String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        Matcher matcher = TENANT_HOST.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return resolveAt(matcher.group(1), "https://" + normalized);
    }

    /**
     * Проверка кандидатов на явной базе. Отделено от {@link #resolve(String)}, чтобы тест
     * мог направить пробы на локальную заглушку (её хост не соответствует шаблону).
     *
     * @param tenant  тенант (левая метка хоста)
     * @param baseUrl origin хоста тенанта без завершающего {@code /}
     */
    Optional<WorkdayBoard> resolveAt(String tenant, String baseUrl) {
        int probes = 0;
        for (String site : candidatesFor(tenant)) {
            if (probes >= maxSiteProbes) {
                log.debug("Workday {}: бюджет проб ({}) исчерпан", tenant, maxSiteProbes);
                break;
            }
            probes++;
            String url = baseUrl + "/wday/cxs/" + tenant + "/" + site + "/jobs";
            if (isWorkdayJobList(url)) {
                return Optional.of(new WorkdayBoard(tenant, site, baseUrl));
            }
        }
        return Optional.empty();
    }

    /**
     * Кандидаты имени сайта для тенанта: подстановка плейсхолдеров, отбраковка
     * некорректных имён, дедуп <b>без учёта регистра</b> (cxs к регистру site не
     * чувствителен) с сохранением порядка и первого написания.
     */
    List<String> candidatesFor(String tenant) {
        String capitalized = tenant.isEmpty()
                ? tenant
                : tenant.substring(0, 1).toUpperCase(Locale.ROOT) + tenant.substring(1);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String template : siteCandidates) {
            String site = template.strip()
                    .replace("{tenant}", tenant)
                    .replace("{Tenant}", capitalized);
            if (SITE.matcher(site).matches() && seen.add(site.toLowerCase(Locale.ROOT))) {
                result.add(site);
            }
        }
        return List.copyOf(result);
    }

    /** Ответ по адресу — список вакансий Workday (числовой {@code total} + массив {@code jobPostings}). */
    private boolean isWorkdayJobList(String url) {
        String body;
        try {
            body = httpClient.postJson(url, PROBE_BODY);
        } catch (HttpClientErrorException clientError) {
            if (clientError.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                throw clientError; // лимит источника — не «сайта нет»
            }
            log.debug("Workday-проб {}: {} — не этот сайт", url, clientError.getStatusCode());
            return false;
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("total").isNumber() && root.path("jobPostings").isArray();
        } catch (JsonProcessingException notJson) {
            return false;
        }
    }
}
