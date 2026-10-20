package com.roleorienta.api.vacancy;

import com.roleorienta.api.vacancy.VacancyDtos.VacancyDetails;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyItem;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyPage;
import com.roleorienta.api.vacancy.VacancyDtos.Work;
import com.roleorienta.api.vacancy.VacancyRepository.ActiveCondition;
import com.roleorienta.api.vacancy.VacancyRepository.PageWindow;
import com.roleorienta.api.vacancy.VacancyRepository.VacancyRow;
import com.roleorienta.api.vacancy.VacancyRepository.WorkFacts;
import java.time.Instant;
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
 *   <li>Списки — страницами по номеру с общим числом (решение владельца 2026-10-20, §151): номер меньше 1 —
 *       первая страница, размер обрезается до 1…{@value #MAX_SIZE}; страница за концом списка — пустая, с общим
 *       числом.</li>
 *   <li>Сведения и отметки — только для вакансий, которые выдавались пользователю или отмечены им; иначе пусто
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

    private static final String CONFLICT = "CONFLICT";
    private static final String REMOTE = "REMOTE";

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
     * @param page   номер страницы, с 1
     * @param size   строк на странице
     * @return страница накопленного списка и общее число; условия не заданы — пусто
     */
    public VacancyPage delivered(long userId, int page, int size) {
        int number = Math.max(FIRST_PAGE, page);
        int rows = size(size);
        Optional<ActiveCondition> condition = repository.activeCondition(userId);
        if (condition.isEmpty()) {
            return new VacancyPage(List.of(), number, rows, 0);
        }
        Instant visibleSince = Instant.now().minus(properties.hideAfter());
        long conditionId = condition.get().id();
        List<VacancyRow> shown = repository.delivered(conditionId, visibleSince, window(number, rows));
        return new VacancyPage(items(shown, condition.get().workFormat()), number, rows,
                repository.countDelivered(conditionId, visibleSince));
    }

    /**
     * @param userId пользователь
     * @param page   номер страницы, с 1
     * @param size   строк на странице
     * @return страница отмеченных вакансий и общее число
     */
    public VacancyPage marked(long userId, int page, int size) {
        int number = Math.max(FIRST_PAGE, page);
        int rows = size(size);
        List<VacancyRow> shown = repository.marked(userId, window(number, rows));
        return new VacancyPage(items(shown, conditionFormat(userId)), number, rows, repository.countMarked(userId));
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

    /** Окно страницы: смещение в {@code long} — номер страницы, умноженный на размер, не переполняется. */
    private static PageWindow window(int page, int size) {
        return new PageWindow((long) (page - FIRST_PAGE) * size, size);
    }

    private static List<VacancyItem> items(List<VacancyRow> rows, String conditionFormat) {
        return rows.stream().map(vacancy -> new VacancyItem(vacancy.id(), vacancy.title(), vacancy.parties(),
                work(vacancy.work(), conditionFormat), vacancy.publication().opened())).toList();
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

    private static int size(int requested) {
        return Math.max(1, Math.min(MAX_SIZE, requested));
    }
}
