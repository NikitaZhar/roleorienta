package com.roleorienta.api.vacancy;

import com.roleorienta.api.account.UserAccountRepository;
import com.roleorienta.api.condition.InvalidFieldException;
import com.roleorienta.api.vacancy.StatusRules.Change;
import com.roleorienta.api.vacancy.StatusRules.Line;
import com.roleorienta.api.vacancy.VacancyDtos.HistoryLine;
import com.roleorienta.api.vacancy.VacancyDtos.ResultFilter;
import com.roleorienta.api.vacancy.VacancyDtos.ResultFilters;
import com.roleorienta.api.vacancy.VacancyDtos.Posting;
import com.roleorienta.api.vacancy.VacancyDtos.Publication;
import com.roleorienta.api.vacancy.VacancyDtos.Timeline;
import com.roleorienta.api.vacancy.VacancyDtos.UserStatus;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyDetails;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyItem;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyPage;
import com.roleorienta.api.vacancy.VacancyDtos.Work;
import com.roleorienta.api.vacancy.VacancyRepository.Counts;
import com.roleorienta.api.vacancy.VacancyRepository.PageWindow;
import com.roleorienta.api.vacancy.VacancyRepository.RowState;
import com.roleorienta.api.vacancy.VacancyRepository.StoredLine;
import com.roleorienta.api.vacancy.VacancyRepository.VacancyRow;
import com.roleorienta.api.vacancy.VacancyRepository.WorkFacts;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Результаты поиска, «Мои отклики», «Не интересно», сведения о вакансии и статусы вакансии у пользователя
 * (бизнес-описание §4.5, §6; технический документ §8; стенограмма §156).
 *
 * <ul>
 *   <li>Результаты — вакансии, выданные по активной версии условий и видимые сейчас (не закрыта, подтверждена не
 *       раньше {@link VacancyProperties#hideAfter()}, подходит по текущим условиям), со статусом «Новая» или
 *       «Просмотрена»; открытые позже сверху. Условия не заданы — пусто.</li>
 *   <li>«Мои отклики» — действующие отклики по открытым вакансиям, ниже отдельно — завершённые: «Работодатель отказал»
 *       и отклики по закрытым работодателем (§196, §213), «Не интересно» —
 *       {@link StatusEvent#NOT_INTERESTED}, по любым
 *       условиям, и закрытые работодателем; статус изменён позже сверху.</li>
 *   <li>Списки — страницами по номеру с общим числом (решение владельца 2026-10-20, §151): номер меньше 1 —
 *       первая страница, размер обрезается до 1…{@value #MAX_SIZE}; страница за концом списка — пустая, с общим
 *       числом.</li>
 *   <li>Сведения и статусы — только для вакансий, которые выдавались пользователю или имеют его статус; иначе пусто
 *       ({@code 404}: чужая или неизвестная вакансия неотличимы).</li>
 * </ul>
 */
@Service
public class VacancyService {

    /** Строк на странице по умолчанию и наибольшее (экран предлагает 20, 50, 100). */
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;
    /** Первая страница. */
    static final int FIRST_PAGE = 1;

    private static final List<StatusEvent> NOT_INTERESTED = List.of(StatusEvent.NOT_INTERESTED);

    /** Код «Новая» — статуса пользователя ещё нет. */
    private static final String NEW = "NEW";
    /** Строки истории, которые собираются из данных, а не хранятся. */
    private static final String RECEIVED = "RECEIVED";
    private static final String CLOSED = "CLOSED";

    private static final String CONFLICT = "CONFLICT";
    private static final String REMOTE = "REMOTE";

    private final VacancyRepository repository;
    private final VacancyProperties properties;
    private final UserAccountRepository accounts;

    /**
     * @param repository данные списков и статусов
     * @param properties срок без подтверждения
     * @param accounts   блокировка строки пользователя
     */
    public VacancyService(VacancyRepository repository, VacancyProperties properties,
            UserAccountRepository accounts) {
        this.repository = repository;
        this.properties = properties;
        this.accounts = accounts;
    }

    /**
     * Открытие результатов — заход в список (§191). Одно {@code UPDATE} без своей транзакции (фиксируется сразу), до
     * чтения страницы и вне её транзакции: при одновременных открытиях второе {@code UPDATE} строки пользователя ждёт
     * первое и выполняется, а внутри {@code REPEATABLE READ} оно завершилось бы ошибкой сериализации.
     *
     * @param userId пользователь
     * @return граница текущего захода; {@code null} — первый заход
     */
    public Instant visit(long userId) {
        return repository.recordVisit(userId, properties.visitGap());
    }

    /**
     * Страница и числа читаются одним снимком данных: транзакция только для чтения с уровнем
     * {@code REPEATABLE READ} — выдача job-worker, добавившая порцию между двумя запросами, не разводит «Found N» и
     * строки (аудит §154). https://www.postgresql.org/docs/16/transaction-iso.html#XACT-REPEATABLE-READ
     *
     * @param userId    пользователь
     * @param view      страница и язык названий групп
     * @param visitFrom граница текущего захода ({@link #visit}): вакансии, полученные позже, — с точкой (§191);
     *                  {@code null} — без точек
     * @param filter    отбор внутри результатов: позиция условий (§194) и «после прошлого захода» (§200; прошлого захода
     *                  не было — пусто); общее число и число новых — по отобранным
     * @return страница результатов, общее число и число новых; условия не заданы — пусто
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VacancyPage delivered(long userId, View view, Instant visitFrom, ResultFilter filter) {
        int number = Math.max(FIRST_PAGE, view.page());
        int rows = size(view.size());
        Optional<Long> condition = repository.activeCondition(userId);
        if (condition.isEmpty()) {
            return new VacancyPage(List.of(), number, rows, 0, 0);
        }
        Instant visibleSince = Instant.now().minus(properties.hideAfter());
        long conditionId = condition.get();
        Instant recentFrom = !filter.sinceLastVisit() ? null : visitFrom != null ? visitFrom : Instant.now();
        List<VacancyRow> shown = repository.delivered(conditionId, visibleSince, window(number, rows),
                filter.position(), recentFrom);
        Counts counts = repository.countDelivered(conditionId, visibleSince, filter.position(), recentFrom);
        Map<Long, List<String>> matched = repository.matchedPositions(conditionId,
                shown.stream().map(VacancyRow::id).toList(), view.language());
        return new VacancyPage(items(shown, visitFrom, matched), number, rows,
                counts.total(), counts.fresh());
    }

    /**
     * Метки отбора над результатами: позиции и группы текущих условий с числом видимых вакансий (§194, §204) и сколько
     * вакансий пришло после прошлого захода (§200).
     *
     * @param userId    пользователь
     * @param visitFrom граница текущего захода ({@link #visit}); {@code null} — первый заход: после прошлого — 0
     * @param language  язык названий групп
     * @return метки; условия не заданы — пусто и 0
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResultFilters filters(long userId, Instant visitFrom, String language) {
        Optional<Long> condition = repository.activeCondition(userId);
        if (condition.isEmpty()) {
            return new ResultFilters(List.of(), 0);
        }
        Instant visibleSince = Instant.now().minus(properties.hideAfter());
        long recent = visitFrom == null ? 0
                : repository.countDelivered(condition.get(), visibleSince, null, visitFrom).total();
        return new ResultFilters(repository.positionCounts(condition.get(), visibleSince, language), recent);
    }

    /**
     * «Мои отклики»; страница и число — одним снимком данных, как в {@link #delivered}.
     *
     * @param userId   пользователь
     * @param page     номер страницы, с 1
     * @param size     строк на странице
     * @param finished {@code false} — действующие отклики («Applied», «Interview») по открытым вакансиям; {@code true}
     *                 — завершённые (§196, §213: ниже действующих, свёрнуто): «Employer declined» и отклики по
     *                 вакансиям, закрытым работодателем
     * @return страница откликов и общее число
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VacancyPage responses(long userId, int page, int size, boolean finished) {
        int number = Math.max(FIRST_PAGE, page);
        int rows = size(size);
        List<VacancyRow> shown = repository.responses(userId, finished, window(number, rows));
        return new VacancyPage(items(shown, null, Map.of()), number, rows,
                repository.countResponses(userId, finished), 0);
    }

    /**
     * «Не интересно»; страница и число — одним снимком данных, как в {@link #delivered}.
     *
     * @param userId пользователь
     * @param page   номер страницы, с 1
     * @param size   строк на странице
     * @return страница вакансий со статусом «Не интересно» и общее число
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VacancyPage notInterested(long userId, int page, int size) {
        return byStatus(userId, NOT_INTERESTED, page, size);
    }

    /**
     * Сведения и история одним снимком данных.
     *
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return сведения со статусом, историей и требованиями к языку; пусто — вакансия не выдавалась пользователю и
     *         нет его статуса
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<VacancyDetails> details(long userId, long vacancyId) {
        return repository.accessible(userId, vacancyId).map(row -> new VacancyDetails(row.id(), row.title(),
                row.parties(), work(row.work()), new Publication(row.state().url(), timeline(row.state(), null),
                        history(row.state(), repository.history(userId, vacancyId)),
                        repository.languages(vacancyId))));
    }

    /**
     * Смена статуса по правилам {@link StatusRules}; повтор того же запроса ничего не меняет. Одна транзакция:
     * строка пользователя блокируется, затем читаются текущий статус и история и пишется изменение.
     *
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @param requested статус
     * @param zone      часовой пояс браузера (IANA): по нему — день для замены статуса того же дня
     * @return {@code false} — вакансия не выдавалась пользователю и нет его статуса
     * @throws InvalidFieldException часовой пояс неизвестен ({@code 400}, поле {@code /zone})
     */
    @Transactional
    public boolean setStatus(long userId, long vacancyId, StatusEvent requested, String zone) {
        LocalDate today = LocalDate.now(zone(zone));
        accounts.lock(userId);
        if (!repository.isAccessible(userId, vacancyId)) {
            return false;
        }
        List<Line> history = repository.history(userId, vacancyId).stream()
                .map(line -> new Line(line.id(), line.event(), line.day())).toList();
        Change change = StatusRules.change(repository.currentStatus(userId, vacancyId).orElse(null), history,
                requested, today);
        if (change.line() == null) {
            return true;
        }
        Instant now = Instant.now();
        if (change.replaced() != null) {
            repository.replaceLine(change.replaced(), change.line(), now);
        } else {
            repository.appendLine(userId, vacancyId, change.line(), now, today);
        }
        repository.saveCurrent(userId, vacancyId, change.current(), now);
        return true;
    }

    private VacancyPage byStatus(long userId, List<StatusEvent> statuses, int page, int size) {
        int number = Math.max(FIRST_PAGE, page);
        int rows = size(size);
        List<VacancyRow> shown = repository.byStatus(userId, statuses, window(number, rows));
        return new VacancyPage(items(shown, null, Map.of()), number, rows,
                repository.countByStatus(userId, statuses), 0);
    }

    private static ZoneId zone(String zone) {
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException unknown) {
            throw new InvalidFieldException("/zone", "Unknown time zone");
        }
    }

    /** Окно страницы: смещение в {@code long} — номер страницы, умноженный на размер, не переполняется. */
    private static PageWindow window(int page, int size) {
        return new PageWindow((long) (page - FIRST_PAGE) * size, size);
    }

    /**
     * @param visitFrom граница текущего захода в результаты (§191); {@code null} — точки не ставятся
     * @param matched   к каким позициям условий подошла вакансия (§194); нет вакансии — пусто
     */
    private static List<VacancyItem> items(List<VacancyRow> rows, Instant visitFrom, Map<Long, List<String>> matched) {
        return rows.stream().map(vacancy -> new VacancyItem(vacancy.id(),
                new Posting(vacancy.title(), vacancy.state().url(), matched.getOrDefault(vacancy.id(), List.of())),
                vacancy.parties(),
                work(vacancy.work()), timeline(vacancy.state(), visitFrom))).toList();
    }

    /**
     * Текущий статус: нет — «Новая» с моментом первой выдачи. После прошлого захода (§191) — впервые получена
     * пользователем позже границы захода.
     */
    private static Timeline timeline(RowState state, Instant visitFrom) {
        UserStatus status = state.current() == null
                ? new UserStatus(NEW, state.receivedAt(), state.closedAt() != null)
                : new UserStatus(state.current().status().name(), state.current().changedAt(),
                        state.closedAt() != null);
        boolean sinceLastVisit = visitFrom != null && state.receivedAt() != null
                && state.receivedAt().isAfter(visitFrom);
        return new Timeline(state.opened(), status, sinceLastVisit);
    }

    /**
     * История по времени: «Получена» (первая выдача), хранимые строки, «Закрыта работодателем». При равном времени
     * порядок сохраняется (сортировка устойчивая).
     */
    private static List<HistoryLine> history(RowState state, List<StoredLine> stored) {
        List<HistoryLine> lines = new ArrayList<>();
        if (state.receivedAt() != null) {
            lines.add(new HistoryLine(RECEIVED, state.receivedAt()));
        }
        stored.forEach(line -> lines.add(new HistoryLine(line.event().name(), line.at())));
        if (state.closedAt() != null) {
            lines.add(new HistoryLine(CLOSED, state.closedAt()));
        }
        lines.sort(Comparator.comparing(HistoryLine::at));
        return lines;
    }

    /**
     * Противоречивый формат — {@code null} (на экране строки формата нет, §192); территория удалённой работы — страны
     * вакансии с форматом {@code REMOTE}; страна не определена — стран нет (нераспознанное место рядом с
     * определённой страной пометки не даёт: страна известна, аудит §154).
     */
    private static Work work(WorkFacts facts) {
        String format = CONFLICT.equals(facts.format()) ? null : facts.format();
        return new Work(facts.countries(), format, REMOTE.equals(format) ? facts.countries() : null,
                facts.countries().isEmpty());
    }

    private static int size(int requested) {
        return Math.max(1, Math.min(MAX_SIZE, requested));
    }

    /**
     * Как показать результаты.
     *
     * @param page     номер страницы, с 1 (меньше — первая)
     * @param size     строк на странице
     * @param language язык названий групп ISCO-08 в метках (§204)
     */
    public record View(int page, int size, String language) {
    }
}
