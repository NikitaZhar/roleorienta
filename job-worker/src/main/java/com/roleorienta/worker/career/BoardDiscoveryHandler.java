package com.roleorienta.worker.career;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.CountryNames;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.site.CommonCrawlClient;
import com.roleorienta.worker.site.IndexBlock;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Задание {@code BOARD_DISCOVERY}: обратный путь — от досок систем найма к источникам (технический
 * документ §5.1, вход «индекс систем найма»; решение владельца, §27).
 *
 * <ol>
 *   <li>Из индекса последнего обхода Common Crawl берутся адреса досок Workday
 *       ({@code *.myworkdayjobs.com}), Greenhouse ({@code boards.greenhouse.io},
 *       {@code job-boards.greenhouse.io}), Personio ({@code *.jobs.personio.de|com}) и SmartRecruiters
 *       ({@code careers|jobs.smartrecruiters.com}) — по префиксам SURT, проходами (у каждого прохода
 *       свои блоки); доски из адресов ({@link CareerLinks#board}) записываются. Блок индекса и его
 *       доски — одна транзакция.</li>
 *   <li>Каждая новая доска проверяется на публикации в активных странах сбора (бизнес-описание §4.1):
 *       провайдер отвечает одним запросом, если умеет ({@link SourceAdapter#hasPostingsIn}: Workday —
 *       фасет страны); иначе доска читается один раз и нужна публикация с местом в стране. Первая
 *       такая страна — доска подключается как источник этой страны. Мусор прежних правил разбора адресов ({@code …/robots},
 *       {@code …/es}) отмечается проверенным без запроса. Доски проверяются по очереди провайдеров —
 *       медленная проверка одного не задерживает другие.
 *       Связи с компанией нет: принадлежность юрлицу не подтверждена, вакансии нужнее (решение
 *       владельца). Перепроверка — через {@link CareerProperties#recheckAfter()}.</li>
 * </ol>
 *
 * <p>За задание — {@link CareerProperties#boardsPerTask()} блоков или досок, но проверка досок — не
 * дольше {@link CareerProperties#checkTimeBudget()} (задание укладывается в аренду), дальше — следующее
 * задание. Common Crawl временно не отвечает — проверка найденных досок продолжается по последнему
 * записанному обходу; чтение блоков индекса — повтор задания.</p>
 */
@Component
public class BoardDiscoveryHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "BOARD_DISCOVERY";

    /** Префиксы SURT хостов досок, по возрастанию. */
    /**
     * Проходы индекса: префиксы SURT по возрастанию и назначение их блоков. Новый провайдер — новый
     * проход: уже пройденный обход индекса по новым префиксам просматривается отдельно, без
     * ожидания следующего обхода Common Crawl.
     */
    private static final List<IndexPass> PASSES = List.of(
            new IndexPass(BoardDiscoveryRepository.PURPOSE, List.of("com,myworkdayjobs,", "com,personio,jobs,",
                    "de,personio,jobs,", "io,greenhouse,boards)", "io,greenhouse,job-boards)")),
            new IndexPass("BOARD_SR", List.of("com,smartrecruiters,careers)", "com,smartrecruiters,jobs)")));
    private static final String SLOVAKIA = "SK";
    private static final String PAYLOAD = "{}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(BoardDiscoveryHandler.class);

    private final CommonCrawlClient client;
    private final BoardDiscoveryRepository repository;
    private final Map<String, SourceAdapter> adaptersByProvider;
    private final TaskService taskService;
    private final CareerProperties properties;

    /**
     * @param client      Common Crawl
     * @param repository  блоки и доски
     * @param adapters    адаптеры провайдеров
     * @param taskService постановка следующего задания
     * @param properties  досок за задание, срок перепроверки
     */
    public BoardDiscoveryHandler(CommonCrawlClient client, BoardDiscoveryRepository repository,
            ObjectProvider<SourceAdapter> adapters, TaskService taskService, CareerProperties properties) {
        this.client = client;
        this.repository = repository;
        this.adaptersByProvider = adapters.orderedStream()
                .collect(Collectors.toMap(SourceAdapter::provider, Function.identity()));
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * @param suffix часть ключа: дата постановки или место обратного пути
     * @return ключ задания
     */
    public static String taskKey(String suffix) {
        return "board-discovery:" + suffix;
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
            Optional<String> latest = latestCrawl();
            if (latest.isEmpty()) {
                return new TaskOutcome.Retry("Common Crawl index is not available", Duration.ZERO);
            }
            String crawl = latest.get();
            int budget = properties.boardsPerTask();
            for (IndexPass pass : PASSES) {
                if (!repository.hasBlocks(crawl, pass.purpose())) {
                    List<IndexBlock> blocks = client.blocks(crawl, pass.prefixes());
                    repository.insertBlocks(crawl, pass.purpose(), blocks);
                    LOG.info("Common Crawl {}: {} index blocks of {}", crawl, blocks.size(), pass.purpose());
                }
                List<IndexBlock> blocks = repository.nextBlocks(crawl, pass.purpose(), budget);
                for (IndexBlock block : blocks) {
                    repository.completeBlock(crawl, pass.purpose(), block.seq(), boards(client.block(crawl, block)));
                }
                if (!blocks.isEmpty()) {
                    enqueue(crawl + ":" + pass.purpose() + ":" + blocks.get(blocks.size() - 1).seq());
                    return new TaskOutcome.Done();
                }
            }
            List<String> countries = repository.activeCountries();
            List<Board> toCheck = countries.isEmpty() ? List.of()
                    : repository.boardsToCheck(budget, properties.recheckAfter());
            long deadline = System.nanoTime() + properties.checkTimeBudget().toNanos();
            int checked = 0;
            for (Board board : toCheck) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                Optional<String> country = countryWithPostings(board, countries);
                repository.recordCheck(board, country.orElse(null));
                checked++;
                LOG.info("Board {} {}: postings in {}", board.provider(), board.board(), country.orElse("-"));
            }
            if (toCheck.size() == budget || checked < toCheck.size()) {
                Board last = toCheck.get(Math.max(checked - 1, 0));
                enqueue(crawl + ":check:" + last.provider() + ":" + last.board() + ":" + checked);
            }
            return new TaskOutcome.Done();
        } catch (IOException exception) {
            return new TaskOutcome.Retry("Common Crawl not read: " + exception.getMessage(), Duration.ZERO);
        }
    }

    /**
     * Последний обход Common Crawl; индекс не отвечает — последний обход, чьи блоки уже записаны:
     * проверка найденных досок от Common Crawl не зависит и не должна вставать вместе с ним.
     */
    private Optional<String> latestCrawl() {
        try {
            return Optional.of(client.latestCrawl());
        } catch (IOException unavailable) {
            LOG.warn("Common Crawl index not read, continue with the known crawl: {}", unavailable.getMessage());
            return repository.lastCrawl();
        }
    }

    private void enqueue(String suffix) {
        taskService.enqueue(TYPE, taskKey(suffix), PAYLOAD);
    }

    /**
     * Доски из адресов блока индекса (строка: {@code <ключ> <время> <JSON с url>}).
     */
    private static Set<Board> boards(List<String> lines) {
        Set<Board> boards = new LinkedHashSet<>();
        for (String line : lines) {
            int json = line.indexOf('{');
            if (json < 0) {
                continue;
            }
            try {
                CareerLinks.board(JSON.readTree(line.substring(json)).path("url").asText()).ifPresent(boards::add);
            } catch (JsonProcessingException malformed) {
                LOG.debug("Malformed index line skipped");
            }
        }
        return boards;
    }

    /**
     * Первая страна сбора, где у доски есть публикации: провайдер отвечает одним запросом, если умеет;
     * иначе доска читается один раз и проверяются места публикаций.
     */
    private Optional<String> countryWithPostings(Board board, List<String> countries) {
        SourceAdapter adapter = adaptersByProvider.get(board.provider());
        if (adapter == null || !CareerLinks.isBoard(board)) {
            return Optional.empty();
        }
        List<String> locations = null;
        for (String country : countries) {
            Optional<Boolean> answer = adapter.hasPostingsIn(board.board(), country);
            if (answer.isEmpty() && locations == null) {
                locations = locations(adapter, board);
            }
            boolean found = answer.isPresent() ? answer.get()
                    : locations.stream().anyMatch(location -> inCountry(location, country));
            if (found) {
                return Optional.of(country);
            }
        }
        return Optional.empty();
    }

    private static List<String> locations(SourceAdapter adapter, Board board) {
        return adapter.read(board.board()) instanceof SourceReadResult.Read read
                ? read.postings().stream().map(FetchedPosting::location).filter(Objects::nonNull).toList()
                : List.of();
    }

    /**
     * Место публикации в стране: для Словакии — и по крупным городам ({@link SlovakLocations}), для
     * остальных — по названию страны.
     */
    private static boolean inCountry(String location, String country) {
        return SLOVAKIA.equals(country) ? SlovakLocations.matches(location) : CountryNames.mentions(location, country);
    }

    /**
     * @param purpose  назначение блоков
     * @param prefixes префиксы SURT по возрастанию
     */
    private record IndexPass(String purpose, List<String> prefixes) {
    }
}
