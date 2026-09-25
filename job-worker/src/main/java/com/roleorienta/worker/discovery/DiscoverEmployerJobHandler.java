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
import com.roleorienta.worker.adapters.BoardProfile;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.discovery.EmployerSourceRegistrar.Registration;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.jobs.TypedJobHandler;
import com.roleorienta.worker.http.SourceBackoffException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

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
 *       {@code OUT_OF_MARKET} (без очереди подтверждения); нет фасета стран — по фасету
 *       локаций (§59), нет и его — по локациям вакансий первой страницы (§73: выборка,
 *       иначе тенанты без фасетов засоряли очередь ручной проверки);</li>
 *   <li><b>принадлежность доски</b> (A2, §79): перед авто-подключением сведения о доске от
 *       провайдера ({@link SourceAdapter#boardProfile}) сверяются
 *       {@link BoardOwnershipProperties}; сомнение (нет описания, признак кадрового
 *       агентства, описание не называет владельца) — {@code LOW} и {@code PENDING};</li>
 *   <li>лента не открывается окончательно ({@code 403}/{@code 404}/{@code 410}/{@code 422},
 *       §74) — {@code NONE} и состояние {@code UNREACHABLE}: доски по адресу из индекса нет
 *       или доступ закрыт, в очередь подтверждения не попадает;</li>
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

    /** Сколько значений (стран, локаций) называет обоснование решения гейта. */
    private static final int SUMMARY_ITEMS = 3;

    private static final Logger log = LoggerFactory.getLogger(DiscoverEmployerJobHandler.class);

    /** Ответы, после которых доска считается недоступной (см. {@link #isUnreachable}). */
    private static final Set<Integer> UNREACHABLE_STATUSES = Set.of(403, 404, 410, 422);

    /** Сводка Workday вместо локации: «2 Locations», «18 Locations». */
    private static final Pattern LOCATIONS_SUMMARY = Pattern.compile("\\d+\\s+Locations?", Pattern.CASE_INSENSITIVE);

    private final SourceAdapterRegistry adapterRegistry;
    private final EmployerCandidateRepository candidateRepository;
    private final EmployerSourceRegistrar sourceRegistrar;
    private final DiscoveryMarketProperties market;
    private final BoardOwnershipProperties ownership;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param adapterRegistry     реестр адаптеров источников (выбор по коду провайдера)
     * @param candidateRepository очередь кандидатов
     * @param sourceRegistrar     авто-подключение уверенного кандидата
     * @param market              целевой рынок пилота (гейт рынка, §56)
     * @param ownership           проверка принадлежности доски (A2, §79)
     */
    public DiscoverEmployerJobHandler(
            SourceAdapterRegistry adapterRegistry,
            EmployerCandidateRepository candidateRepository,
            EmployerSourceRegistrar sourceRegistrar,
            DiscoveryMarketProperties market,
            BoardOwnershipProperties ownership) {
        this.adapterRegistry = adapterRegistry;
        this.candidateRepository = candidateRepository;
        this.sourceRegistrar = sourceRegistrar;
        this.market = market;
        this.ownership = ownership;
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
        candidate.setState(assessment.state());

        if (assessment.state() == EmployerCandidateState.CONFIRMED) {
            String companyName = assessment.companyName() != null ? assessment.companyName() : payload.slug();
            Registration registration = sourceRegistrar.register(
                    payload.providerCode(), payload.slug(), payload.baseUrl(), companyName);
            candidate.setCompanyId(registration.companyId());
            candidate.setSourceId(registration.sourceId());
            candidateRepository.save(candidate);
            log.info("DISCOVER_EMPLOYER: {}/{} → HIGH, авто-подключение (source={}), публикаций {}",
                    payload.providerCode(), payload.slug(),
                    registration.sourceId(), assessment.postingCount());
            return;
        }
        candidateRepository.save(candidate);
        log.info("DISCOVER_EMPLOYER: {}/{} → {} {} ({})", payload.providerCode(), payload.slug(),
                assessment.state(), assessment.confidence(), assessment.reason());
    }

    /**
     * Проверяет ленту кандидата и возвращает уверенность, обоснование и итоговое состояние.
     * Ошибки чтения и разбора перехватываются — обнаружение не должно падать на «плохом»
     * кандидате: доска недоступна ({@link #isUnreachable}) — {@code UNREACHABLE}, прочие —
     * {@code NONE} в очередь подтверждения.
     * Исключение — временные сбои источника ({@link #isTransient}): они пробрасываются,
     * и задание повторяется (§57).
     */
    private Assessment assess(Payload payload) {
        try {
            SourceAdapter adapter = adapterRegistry.forProviderCode(payload.providerCode());
            Source probe = probeSource(payload);
            return withOwnership(adapter, probe, assessFeed(adapter, probe));
        } catch (RuntimeException exception) {
            if (isTransient(exception)) {
                // Сбой источника, а не свойство ленты (§57): не записываем кандидата как NONE
                // (дедуп не дал бы проверить его снова) — пусть задание повторит брокер.
                throw exception;
            }
            if (isUnreachable(exception)) {
                // Доски по этому адресу нет или доступ закрыт (§74): человеку проверять нечего.
                return new Assessment(DiscoveryConfidence.NONE,
                        "лента недоступна: " + exception.getMessage(), 0, EmployerCandidateState.UNREACHABLE);
            }
            return new Assessment(DiscoveryConfidence.NONE,
                    "не удалось прочитать/разобрать ленту: " + exception.getMessage(), 0, EmployerCandidateState.PENDING);
        }
    }

    /**
     * Проверка принадлежности доски (A2, §79) — только для кандидата, которого гейт готов
     * подключить: один дополнительный запрос на подключаемую доску, а не на каждую. Страница
     * доски не открылась (кроме временного сбоя) — сомнение, а не {@code UNREACHABLE}: лента
     * ведь читается.
     *
     * @return прежняя оценка с именем работодателя из описания доски (A3, §80) или
     *         {@code LOW}/{@code PENDING} с причиной сомнения
     */
    private Assessment withOwnership(SourceAdapter adapter, Source probe, Assessment assessment) {
        if (assessment.state() != EmployerCandidateState.CONFIRMED) {
            return assessment;
        }
        Optional<BoardProfile> profile;
        try {
            profile = adapter.boardProfile(probe);
        } catch (RuntimeException exception) {
            if (isTransient(exception)) {
                throw exception;
            }
            return doubtful(assessment, "страница доски не открылась: " + exception.getMessage());
        }
        if (profile.isEmpty()) {
            return assessment;
        }
        Optional<String> doubt = ownership.doubt(profile.get());
        if (doubt.isPresent()) {
            return doubtful(assessment, doubt.get());
        }
        return assessment.named(ownership.ownerName(profile.get()).orElse(null));
    }

    private static Assessment doubtful(Assessment assessment, String doubt) {
        return new Assessment(DiscoveryConfidence.LOW, doubt + "; " + assessment.reason(),
                assessment.postingCount(), EmployerCandidateState.PENDING);
    }

    /**
     * Оценка ленты: пустая — {@code LOW}; есть распределение по странам/локациям — гейт рынка;
     * иначе валидная непустая лента — {@code HIGH}. Исключения чтения ленты разбирает
     * {@link #assess}.
     */
    private Assessment assessFeed(SourceAdapter adapter, Source probe) {
        PostingsPage page = adapter.listPostings(probe, null);
        int count = page.postings().size();
        if (count == 0) {
            return new Assessment(DiscoveryConfidence.LOW,
                    "лента валидна, но пустая — требует подтверждения", 0, EmployerCandidateState.PENDING);
        }
        if (market.enabled() && !page.countryCounts().isEmpty()) {
            return assessMarket(page.countryCounts(), count);
        }
        if (market.enabled() && adapter.reportsCountries()) {
            if (!page.locationCounts().isEmpty()) {
                return assessMarketByLocations(page.locationCounts(), count);
            }
            Map<String, Integer> sample = locationsOnPage(page.postings());
            if (!sample.isEmpty()) {
                // Нет фасетов (§73): локации вакансий первой страницы — выборка, не вся лента.
                Assessment bySample = assessMarketByLocations(sample, count);
                return new Assessment(bySample.confidence(),
                        "по вакансиям первой страницы (фасетов нет): " + bySample.reason(),
                        bySample.postingCount(), bySample.state());
            }
            // Ни стран, ни локаций (§58): рынок не проверен — не подключаем вслепую.
            return new Assessment(DiscoveryConfidence.LOW,
                    "лента валидна (" + count + "), но распределение по странам не получено — "
                            + "рынок не проверен, требует подтверждения", count, EmployerCandidateState.PENDING);
        }
        return new Assessment(DiscoveryConfidence.HIGH,
                "лента валидна, публикаций: " + count, count, EmployerCandidateState.CONFIRMED);
    }

    /**
     * Доска недоступна окончательно: {@code 403} (доступ закрыт), {@code 404}/{@code 410} (сайта
     * нет), {@code 422} (Workday так отвечает на несуществующий сайт/тенант — проверено на
     * стенде §73/§74: страница такого тенанта уводит на community.workday.com). Повтор не
     * поможет, ручная проверка — тоже.
     */
    static boolean isUnreachable(RuntimeException exception) {
        return exception instanceof HttpClientErrorException client
                && UNREACHABLE_STATUSES.contains(client.getStatusCode().value());
    }

    /**
     * Временный сбой источника: пауза вежливости, {@code 429}, {@code 5xx}, сетевой сбой
     * или тайм-аут. В отличие от «лента не разбирается» или {@code 404}, он ничего не
     * говорит о кандидате.
     */
    static boolean isTransient(RuntimeException exception) {
        return exception instanceof SourceBackoffException
                || exception instanceof HttpServerErrorException
                || exception instanceof HttpClientErrorException.TooManyRequests
                || exception instanceof ResourceAccessException;
    }

    /**
     * Гейт рынка по локациям (§59): фасета стран нет — у Workday так бывает у тенантов с
     * одной страной, — но есть все локации работодателя со счётчиками (§61):
     * однозначно рыночная локация → {@code HIGH}, причина называет <b>совпавшие</b> локации;
     * только неоднозначные («Vienna» без страны) → {@code LOW}/{@code PENDING} на ручную
     * проверку; ни одной → {@code OUT_OF_MARKET} (причина — главные локации).
     */
    private Assessment assessMarketByLocations(Map<String, Integer> locationCounts, int count) {
        DiscoveryMarketProperties.LocationMatch match = market.matchLocations(locationCounts);
        int total = locationCounts.values().stream().mapToInt(Integer::intValue).sum();
        String top = topOf(locationCounts);
        if (match.marketCount() > 0) {
            return new Assessment(DiscoveryConfidence.HIGH, String.format(
                    "лента валидна; фасета стран нет, по локациям на рынке: %d из %d; совпали: %s",
                    match.marketCount(), total, firstOf(match.marketLocations())), count, EmployerCandidateState.CONFIRMED);
        }
        if (match.ambiguousCount() > 0) {
            return new Assessment(DiscoveryConfidence.LOW, String.format(
                    "только неоднозначные локации (%d из %d): %s — страна не указана, требует проверки; "
                            + "главные локации: %s",
                    match.ambiguousCount(), total, firstOf(match.ambiguousLocations()), top), count, EmployerCandidateState.PENDING);
        }
        return new Assessment(DiscoveryConfidence.LOW, String.format(
                "нет локаций на целевом рынке (фасета стран нет — вероятно, одна страна; всего %d); локации: %s",
                total, top), count, EmployerCandidateState.OUT_OF_MARKET);
    }

    /**
     * Локации вакансий страницы со счётчиками (§73) — для тенантов без фасетов. Сводки вида
     * «2 Locations» пропускаются: из них страну не узнать.
     */
    private static Map<String, Integer> locationsOnPage(List<DiscoveredPosting> postings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DiscoveredPosting posting : postings) {
            String location = posting.rawLocation();
            if (location != null && !location.isBlank() && !LOCATIONS_SUMMARY.matcher(location).matches()) {
                counts.merge(location.strip(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static String firstOf(List<String> items) {
        return items.stream().limit(SUMMARY_ITEMS).collect(Collectors.joining(", "));
    }

    private static String topOf(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(SUMMARY_ITEMS)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining(", "));
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
        String top = topOf(countryCounts);
        if (inMarket > 0) {
            return new Assessment(DiscoveryConfidence.HIGH, String.format(
                    "лента валидна; на целевом рынке %s: %d из %d; страны: %s",
                    market.countries(), inMarket, total, top), count, EmployerCandidateState.CONFIRMED);
        }
        return new Assessment(DiscoveryConfidence.LOW, String.format(
                "нет публикаций на целевом рынке %s (всего %d); страны: %s",
                market.countries(), total, top), count, EmployerCandidateState.OUT_OF_MARKET);
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
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось разобрать тело задания DISCOVER_EMPLOYER", exception);
        }
    }

    /** Разобранное тело задания. */
    private record Payload(String providerCode, String slug, String baseUrl) {
    }

    /**
     * Результат проверки ленты кандидата. {@code state} — итоговое состояние кандидата:
     * {@code CONFIRMED} — подключить (только при {@code HIGH}), {@code OUT_OF_MARKET} —
     * отсеян гейтом рынка, {@code UNREACHABLE} — доски нет или доступ закрыт, {@code PENDING} —
     * на ручную проверку. {@code companyName} — имя работодателя из описания доски (A3, §80);
     * {@code null} — не известно, компания получит slug.
     */
    private record Assessment(DiscoveryConfidence confidence, String reason, int postingCount,
                              EmployerCandidateState state, String companyName) {

        Assessment(DiscoveryConfidence confidence, String reason, int postingCount, EmployerCandidateState state) {
            this(confidence, reason, postingCount, state, null);
        }

        Assessment named(String name) {
            return new Assessment(confidence, reason, postingCount, state, name);
        }
    }
}
