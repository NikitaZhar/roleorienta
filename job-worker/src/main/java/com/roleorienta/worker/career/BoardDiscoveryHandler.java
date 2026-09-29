package com.roleorienta.worker.career;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.site.CommonCrawlClient;
import com.roleorienta.worker.site.IndexBlock;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *       {@code job-boards.greenhouse.io}) и Personio ({@code *.jobs.personio.de|com}) — по префиксам
 *       SURT; доски из адресов ({@link CareerLinks#board}) записываются. Блок индекса и его доски —
 *       одна транзакция.</li>
 *   <li>Каждая новая доска читается адаптером провайдера (Workday — с фильтром Словакии); есть
 *       публикация с местом в Словакии ({@link SlovakLocations}) — доска подключается как источник.
 *       Связи с компанией нет: принадлежность юрлицу не подтверждена, вакансии нужнее (решение
 *       владельца). Перепроверка — через {@link CareerProperties#recheckAfter()}.</li>
 * </ol>
 *
 * <p>За задание — {@link CareerProperties#boardsPerTask()} блоков или досок, дальше — следующее
 * задание. Common Crawl временно не отвечает — повтор.</p>
 */
@Component
public class BoardDiscoveryHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "BOARD_DISCOVERY";

    /** Префиксы SURT хостов досок, по возрастанию. */
    private static final List<String> PREFIXES = List.of("com,myworkdayjobs,", "com,personio,jobs,",
            "de,personio,jobs,", "io,greenhouse,boards)", "io,greenhouse,job-boards)");
    private static final String COUNTRY = "SK";
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
            String crawl = client.latestCrawl();
            if (!repository.hasBlocks(crawl)) {
                List<IndexBlock> blocks = client.blocks(crawl, PREFIXES);
                repository.insertBlocks(crawl, blocks);
                LOG.info("Common Crawl {}: {} index blocks of job boards", crawl, blocks.size());
            }
            int budget = properties.boardsPerTask();
            List<IndexBlock> blocks = repository.nextBlocks(crawl, budget);
            for (IndexBlock block : blocks) {
                repository.completeBlock(crawl, block.seq(), boards(client.block(crawl, block)));
            }
            if (!blocks.isEmpty()) {
                enqueue(crawl + ":block:" + blocks.get(blocks.size() - 1).seq());
                return new TaskOutcome.Done();
            }
            List<Board> toCheck = repository.boardsToCheck(budget, properties.recheckAfter());
            for (Board board : toCheck) {
                boolean slovak = hasSlovakPostings(board);
                repository.recordCheck(board, slovak);
                LOG.info("Board {} {}: slovak={}", board.provider(), board.board(), slovak);
            }
            if (toCheck.size() == budget) {
                Board last = toCheck.get(toCheck.size() - 1);
                enqueue(crawl + ":check:" + last.provider() + ":" + last.board());
            }
            return new TaskOutcome.Done();
        } catch (IOException exception) {
            return new TaskOutcome.Retry("Common Crawl not read: " + exception.getMessage(), Duration.ZERO);
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

    private boolean hasSlovakPostings(Board board) {
        SourceAdapter adapter = adaptersByProvider.get(board.provider());
        if (adapter == null) {
            return false;
        }
        return adapter.read(board.board(), COUNTRY) instanceof SourceReadResult.Read read
                && read.postings().stream().anyMatch(posting -> SlovakLocations.matches(posting.location()));
    }
}
