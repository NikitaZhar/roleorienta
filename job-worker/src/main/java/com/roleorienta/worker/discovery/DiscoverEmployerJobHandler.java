package com.roleorienta.worker.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.DiscoveryConfidence;
import com.roleorienta.core.domain.EmployerCandidate;
import com.roleorienta.core.domain.EmployerCandidateState;
import com.roleorienta.core.domain.Provider;
import com.roleorienta.core.domain.ProviderKind;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceKind;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.jobs.TypedJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Обработчик задания {@code DISCOVER_EMPLOYER} — каркас обнаружения работодателей
 * (§5, §6, ADR-6, §33).
 *
 * <p>По кандидату (провайдер + slug + базовый адрес) проверяет ленту через
 * <b>тот же</b> защищённый HTTP-клиент, что и сбор (адаптер провайдера поверх
 * {@code SourceHttpClient} — контур SSRF §32), и заводит {@link EmployerCandidate}
 * в очередь подтверждения. Уверенность выставляется по результату проверки, но в
 * этом срезе <b>ничего не подключается автоматически</b> — все кандидаты идут в
 * очередь (авто-подключение по гейту — следующий срез).</p>
 *
 * <p>Проверка ленты выполняется на <b>непостоянном</b> (не сохранённом) источнике-
 * пробе: адаптеру достаточно базового адреса и slug, чтобы построить URL и разобрать
 * ответ; заведение реального {@code Source} происходит только при подтверждении
 * администратором. Задание не создаёт {@code CrawlRun}/{@code CrawlTask} — источника
 * ещё нет; тип {@code DISCOVER_EMPLOYER} служит лишь ключом маршрутизации сообщения.</p>
 *
 * <p>Дедуп (§5): при уже существующем кандидате (provider, slug) задание идемпотентно
 * пропускается. Любая ошибка чтения/разбора не роняет задание, а фиксируется
 * кандидатом с уверенностью {@code NONE} и причиной — «неуверенный не пропадает».</p>
 */
@Component
public class DiscoverEmployerJobHandler implements TypedJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DiscoverEmployerJobHandler.class);

    private final SourceAdapterRegistry adapterRegistry;
    private final EmployerCandidateRepository candidateRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param adapterRegistry     реестр адаптеров источников (выбор по коду провайдера)
     * @param candidateRepository очередь кандидатов
     */
    public DiscoverEmployerJobHandler(
            SourceAdapterRegistry adapterRegistry,
            EmployerCandidateRepository candidateRepository) {
        this.adapterRegistry = adapterRegistry;
        this.candidateRepository = candidateRepository;
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
        candidate.setState(EmployerCandidateState.PENDING);
        candidate.setConfidence(assessment.confidence());
        candidate.setReason(assessment.reason());
        candidate.setPostingCount(assessment.postingCount());
        candidateRepository.save(candidate);

        log.info("DISCOVER_EMPLOYER: кандидат {}/{} → {} ({}), публикаций {}",
                payload.providerCode(), payload.slug(),
                assessment.confidence(), assessment.reason(), assessment.postingCount());
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
            if (count > 0) {
                return new Assessment(DiscoveryConfidence.HIGH,
                        "лента валидна, публикаций: " + count, count);
            }
            return new Assessment(DiscoveryConfidence.LOW,
                    "лента валидна, но пустая — требует подтверждения", 0);
        } catch (RuntimeException e) {
            return new Assessment(DiscoveryConfidence.NONE,
                    "не удалось прочитать/разобрать ленту: " + e.getMessage(), 0);
        }
    }

    /**
     * Непостоянный источник-проба: адаптеру нужны только базовый адрес и slug для
     * построения URL и разбора ответа. Не сохраняется — реальный {@code Source}
     * заводится лишь при подтверждении.
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

    /** Результат проверки ленты кандидата. */
    private record Assessment(DiscoveryConfidence confidence, String reason, int postingCount) {
    }
}
