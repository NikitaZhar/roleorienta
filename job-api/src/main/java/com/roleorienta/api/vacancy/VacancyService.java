package com.roleorienta.api.vacancy;

import com.roleorienta.api.vacancy.VacancyDtos.VacancyDetails;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyItem;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyPage;
import com.roleorienta.api.vacancy.VacancyDtos.Work;
import com.roleorienta.api.vacancy.VacancyRepository.ActiveCondition;
import com.roleorienta.api.vacancy.VacancyRepository.Cursor;
import com.roleorienta.api.vacancy.VacancyRepository.ListedRow;
import com.roleorienta.api.vacancy.VacancyRepository.VacancyRow;
import com.roleorienta.api.vacancy.VacancyRepository.WorkFacts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Накопленный список, сведения о вакансии и отметки «не подходит» пользователя (бизнес-описание §4.5, §6;
 * технический документ §8).
 *
 * <ul>
 *   <li>Список — вакансии, выданные по активной версии условий и видимые сейчас (не закрыта, подтверждена не
 *       раньше {@link VacancyProperties#hideAfter()}, подходит по текущим условиям, не отмечена); новые сверху.
 *       Условия не заданы — список пуст.</li>
 *   <li>Курсор связан со списком: версия условий (или список отмеченных), время и id последней строки; курсор
 *       другого списка или испорченный — {@link InvalidCursorException}.</li>
 *   <li>Сведения и отметки — только для вакансий, которые выдавались пользователю или отмечены им; иначе пусто
 *       ({@code 404}: чужая или неизвестная вакансия неотличимы).</li>
 * </ul>
 */
@Service
public class VacancyService {

    /** Строк на странице по умолчанию и наибольшее. */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private static final String MARKED_SCOPE = "u";
    private static final String CONDITION_SCOPE = "c";
    private static final String CONFLICT = "CONFLICT";
    private static final String REMOTE = "REMOTE";
    private static final String SEPARATOR = ".";

    private final VacancyRepository repository;
    private final VacancyProperties properties;

    /**
     * @param repository данные списка и отметок
     * @param properties срок без подтверждения
     */
    public VacancyService(VacancyRepository repository, VacancyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * @param userId пользователь
     * @param cursor курсор страницы; {@code null} — первая
     * @param limit  строк на странице (обрезается до 1…{@value #MAX_LIMIT})
     * @return страница накопленного списка
     */
    public VacancyPage delivered(long userId, String cursor, int limit) {
        Optional<ActiveCondition> condition = repository.activeCondition(userId);
        if (condition.isEmpty()) {
            if (cursor != null && !cursor.isEmpty()) {
                throw new InvalidCursorException();
            }
            return new VacancyPage(List.of(), null);
        }
        String scope = CONDITION_SCOPE + condition.get().id();
        int size = size(limit);
        List<ListedRow> rows = repository.delivered(condition.get().id(), userId,
                Instant.now().minus(properties.hideAfter()), decode(cursor, scope), size + 1);
        return page(rows, size, scope, condition.get().workFormat());
    }

    /**
     * @param userId пользователь
     * @param cursor курсор страницы; {@code null} — первая
     * @param limit  строк на странице
     * @return страница отмеченных вакансий
     */
    public VacancyPage marked(long userId, String cursor, int limit) {
        int size = size(limit);
        List<ListedRow> rows = repository.marked(userId, decode(cursor, MARKED_SCOPE), size + 1);
        return page(rows, size, MARKED_SCOPE, conditionFormat(userId));
    }

    /**
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return сведения; пусто — вакансия не выдавалась пользователю и не отмечена им
     */
    public Optional<VacancyDetails> details(long userId, long vacancyId) {
        String format = conditionFormat(userId);
        return repository.accessible(userId, vacancyId).map(row -> new VacancyDetails(row.id(), row.title(),
                row.parties(), work(row.work(), format), row.publication()));
    }

    /**
     * Отметка «не подходит» (идемпотентно): вакансия уходит из списка, отметка сохраняется при смене условий.
     *
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return {@code false} — вакансия не выдавалась пользователю и не отмечена им
     */
    public boolean mark(long userId, long vacancyId) {
        if (repository.accessible(userId, vacancyId).isEmpty()) {
            return false;
        }
        repository.mark(userId, vacancyId);
        return true;
    }

    /**
     * Снятие отметки (идемпотентно): вакансия возвращается в список, если выдавалась по текущим условиям и
     * подходит; иначе войдёт в одну из следующих порций (выдача job-worker).
     *
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return {@code false} — вакансия не выдавалась пользователю и не отмечена им
     */
    public boolean unmark(long userId, long vacancyId) {
        if (repository.accessible(userId, vacancyId).isEmpty()) {
            return false;
        }
        repository.unmark(userId, vacancyId);
        return true;
    }

    private String conditionFormat(long userId) {
        return repository.activeCondition(userId).map(ActiveCondition::workFormat).orElse(null);
    }

    /**
     * Страница из строк, запрошенных с одной лишней ({@code size + 1}): лишняя есть — следующая страница есть, курсор
     * указывает на последнюю показанную; нет — {@code nextCursor} пустой (аудит §65: ровно полная последняя страница
     * давала курсор на пустую).
     */
    private VacancyPage page(List<ListedRow> rows, int size, String scope, String conditionFormat) {
        List<ListedRow> shown = rows.size() > size ? rows.subList(0, size) : rows;
        List<VacancyItem> items = shown.stream().map(row -> item(row, conditionFormat)).toList();
        ListedRow last = shown.isEmpty() ? null : shown.get(shown.size() - 1);
        String next = rows.size() > size ? encode(scope, last.listedAt(), last.vacancy().id()) : null;
        return new VacancyPage(items, next);
    }

    private static VacancyItem item(ListedRow row, String conditionFormat) {
        VacancyRow vacancy = row.vacancy();
        return new VacancyItem(vacancy.id(), vacancy.title(), vacancy.parties(), work(vacancy.work(), conditionFormat),
                row.listedAt());
    }

    /**
     * Противоречивый формат показывается как «не указано» с пометкой; территория удалённой работы — страны
     * вакансии с форматом {@code REMOTE}.
     */
    private static Work work(WorkFacts facts, String conditionFormat) {
        String format = CONFLICT.equals(facts.format()) ? null : facts.format();
        return new Work(facts.countries(), format, REMOTE.equals(format) ? facts.countries() : null,
                facts.countryUncertain() || facts.countries().isEmpty(), conditionFormat != null && format == null);
    }

    private static int size(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static String encode(String scope, Instant at, long id) {
        String plain = scope + SEPARATOR + ChronoUnit.MICROS.between(Instant.EPOCH, at) + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String cursor, String scope) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\\" + SEPARATOR);
            if (parts.length != 3 || !scope.equals(parts[0])) {
                throw new InvalidCursorException();
            }
            long micros = Long.parseLong(parts[1]);
            long id = Long.parseLong(parts[2]);
            if (micros < 0 || id <= 0) {
                throw new InvalidCursorException();
            }
            return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidCursorException();
        }
    }
}
