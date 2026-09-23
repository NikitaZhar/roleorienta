package com.roleorienta.worker.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.core.domain.DiscoveryConfidence;
import com.roleorienta.core.domain.EmployerCandidate;
import com.roleorienta.core.domain.EmployerCandidateState;
import com.roleorienta.core.domain.Provider;
import com.roleorienta.core.domain.ProviderKind;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceKind;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.discovery.EmployerSourceRegistrar.Registration;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.jobs.TypedJobHandler;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Обработчик задания {@code DISCOVER_EMPLOYER} — обнаружение работодателей с гейтом
 * уверенности (§5, §6, ADR-6, §33, §35).
 *
 * <p>По кандидату (провайдер + slug + базовый адрес) проверяет ленту через
 * <b>тот же</b> защищённый HTTP-клиент, что и сбор (адаптер провайдера поверх
 * {@code SourceHttpClient} — контур SSRF §32). <b>Гейт уверенности</b> (ADR-6):</p>
 * <ul>
 *   <li>{@code HIGH} (лента валидна и непуста) — <b>авто-подключение</b>: сразу
 *       заводится {@link Source} в состоянии {@code ACTIVE} через
 *       {@link EmployerSourceRegistrar} ({@code verifiedBy=AUTO}), а кандидат
 *       фиксируется как {@code CONFIRMED} — без ручного подтверждения;</li>
 *   <li><b>гейт рынка</b> (§56): если провайдер сообщает распределение публикаций по
 *       странам, {@code HIGH} даётся только при публикациях на целевом рынке пилота
 *       ({@link DiscoveryMarketProperties}); без них — {@code LOW} и состояние
 *       {@code OUT_OF_MARKET} (без очереди подтверждения);</li>
 *   <li>{@code LOW}/{@code NONE} — кандидат уходит в очередь на подтверждение
 *       ({@code PENDING}): «неуверенный не подключается вслепую и не пропадает».</li>
 * </ul>
 *
 * <p>Проверка ленты выполняется на <b>непостоянном</b> (не сохранённом) источнике-
 * пробе: адаптеру достаточно базового адреса и slug, чтобы построить URL и разобрать
 * ответ. Задание не создаёт {@code CrawlRun}/{@code CrawlTask} — сбор по заведённому
 * источнику ставит планировщик (§6). Дедуп (§5): при уже существующем кандидате
 * (provider, slug) задание идемпотентно пропускается; повторный источник тоже не
 * заводится дважды (см. {@link EmployerSourceRegistrar}).</p>
 */
@Component
public class DiscoverEmployerJobHandler implements TypedJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DiscoverEmployerJobHandler.class);

    private final SourceAdapterRegistry adapterRegistry;
    private final EmployerCandidateRepository candidateRepository;
    private final EmployerSourceRegistrar sourceRegistrar;
    private final DiscoveryMarketProperties market;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param adapterRegistry     реестр адаптеров источников (выбор по коду провайдера)
     * @param candidateRepository очередь кандидатов
     * @param sourceRegistrar     авто-подключение уверенного кандидата
     * @param market              целевой рынок пилота (гейт рынка, §56)
     */
    public DiscoverEmployerJobHandler(
            SourceAdapterRegistry adapterRegistry,
            EmployerCandidateRepository candidateRepository,
            EmployerSourceRegistrar sourceRegistrar,
            DiscoveryMarketProperties market) {
        this.adapterRegistry = adapterRegistry;
        this.candidateRepository = candidateRepository;
        this.sourceRegistrar = sourceRegistrar;
        this.market = market;
    }

    @Override
    public String taskType() {
        return CrawlTaskType.DISCOVER_EMPLOYER.name();
    }

    @Override
    public void handle(JobMessage message) {
        Payload payload = parse(message.payload());

        if (candidateRepository.existsByProviderCodeAndSlug(payload.providerCode(), payload.slug())) {
            log.info("DISCOVER_EMPLOYER: кандидат уже есть ({}/{}), пропуск (дедуп)",
                    payload.providerCode(), payload.slug());
            return;
        }

        Assessment assessment = assess(payload);

        EmployerCandidate candidate = new EmployerCandidate();
        candidate.setProviderCode(payload.providerCode());
        candidate.setSlug(payload.slug());
        candidate.setBaseUrl(payload.baseUrl());
        candidate.setConfidence(assessment.confidence());
        candidate.setReason(assessment.reason());
        candidate.setPostingCount(assessment.postingCount());

        if (assessment.confidence() == DiscoveryConfidence.HIGH) {
            Registration registration = sourceRegistrar.register(
                    payload.providerCode(), payload.slug(), payload.baseUrl(), payload.slug());
            candidate.setState(EmployerCandidateState.CONFIRMED);
            candidate.setCompanyId(registration.companyId());
            candidate.setSourceId(registration.sourceId());
            candidateRepository.save(candidate);
            log.info("DISCOVER_EMPLOYER: {}/{} → HIGH, авто-подключение (source={}), публикаций {}",
                    payload.providerCode(), payload.slug(),
                    registration.sourceId(), assessment.postingCount());
        } else if (assessment.outOfMarket()) {
            candidate.setState(EmployerCandidateState.OUT_OF_MARKET);
            candidateRepository.save(candidate);
            log.info("DISCOVER_EMPLOYER: {}/{} → вне рынка ({})",
                    payload.providerCode(), payload.slug(), assessment.reason());
        } else {
            candidate.setState(EmployerCandidateState.PENDING);
            candidateRepository.save(candidate);
            log.info("DISCOVER_EMPLOYER: {}/{} → {} ({}), в очередь на подтверждение",
                    payload.providerCode(), payload.slug(),
                    assessment.confidence(), assessment.reason());
        }
    }

    /**
     * Проверяет ленту кандидата и возвращает уверенность/обоснование. Ошибки чтения
     * и разбора перехватываются: кандидат всё равно попадает в очередь с
     * уверенностью {@code NONE} — обнаружение не должно падать на «плохом» кандидате.
     */
    private Assessment assess(Payload payload) {
        try {
            SourceAdapter adapter = adapterRegistry.forProviderCode(payload.providerCode());
            PostingsPage page = adapter.listPostings(probeSource(payload), null);
            int count = page.postings().size();
            if (count == 0) {
                return new Assessment(DiscoveryConfidence.LOW,
                        "лента валидна, но пустая — требует подтверждения", 0, false);
            }
            if (market.enabled() && !page.countryCounts().isEmpty()) {
                return assessMarket(page.countryCounts(), count);
            }
            return new Assessment(DiscoveryConfidence.HIGH,
                    "лента валидна, публикаций: " + count, count, false);
        } catch (RuntimeException e) {
            return new Assessment(DiscoveryConfidence.NONE,
                    "не удалось прочитать/разобрать ленту: " + e.getMessage(), 0, false);
        }
    }

    /**
     * Гейт рынка (§56, A2 — минимальная версия): провайдер сообщил распределение всех
     * публикаций источника по странам. Есть публикации на целевом рынке → {@code HIGH};
     * нет → {@code LOW} и {@code OUT_OF_MARKET} (без очереди подтверждения — иначе тысячи
     * досок чужих рынков из автоматического входа затопили бы её). Обоснование называет
     * число на рынке и главные страны — решение объяснимо.
     */
    private Assessment assessMarket(Map<String, Integer> countryCounts, int count) {
        int inMarket = market.marketCount(countryCounts);
        int total = countryCounts.values().stream().mapToInt(Integer::intValue).sum();
        String top = countryCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        if (inMarket > 0) {
            return new Assessment(DiscoveryConfidence.HIGH, String.format(
                    "лента валидна; на целевом рынке %s: %d из %d; страны: %s",
                    market.countries(), inMarket, total, top), count, false);
        }
        return new Assessment(DiscoveryConfidence.LOW, String.format(
                "нет публикаций на целевом рынке %s (всего %d); страны: %s",
                market.countries(), total, top), count, true);
    }

    /**
     * Непостоянный источник-проба: адаптеру нужны только базовый адрес и slug для
     * построения URL и разбора ответа. Не сохраняется — реальный {@code Source}
     * заводит {@link EmployerSourceRegistrar} при авто-подключении.
     */
    private Source probeSource(Payload payload) {
        Provider provider = new Provider();
        provider.setCode(payload.providerCode());
        provider.setDisplayName(payload.providerCode());
        provider.setKind(ProviderKind.ATS);

        Source source = new Source();
        source.setProvider(provider);
        source.setKind(SourceKind.COMPANY_BOARD);
        source.setExternalRef(payload.slug());
        source.setBaseUrl(payload.baseUrl());
        source.setState(SourceState.ACTIVE);
        return source;
    }

    private Payload parse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String providerCode = node.path("providerCode").asText(null);
            String slug = node.path("slug").asText(null);
            String baseUrl = node.path("baseUrl").asText(null);
            if (providerCode == null || slug == null || baseUrl == null
                    || providerCode.isBlank() || slug.isBlank() || baseUrl.isBlank()) {
                throw new IllegalStateException(
                        "DISCOVER_EMPLOYER: в теле задания нужны providerCode, slug, baseUrl");
            }
            return new Payload(providerCode, slug, baseUrl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать тело задания DISCOVER_EMPLOYER", e);
        }
    }

    /** Разобранное тело задания. */
    private record Payload(String providerCode, String slug, String baseUrl) {
    }

    /** Результат проверки ленты кандидата; {@code outOfMarket} — отсеян гейтом рынка. */
    private record Assessment(DiscoveryConfidence confidence, String reason, int postingCount,
                              boolean outOfMarket) {
    }
}
