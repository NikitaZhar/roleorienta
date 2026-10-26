package com.roleorienta.api.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.api.vacancy.StatusRules.Change;
import com.roleorienta.api.vacancy.StatusRules.Line;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Правила смены статуса без БД (бизнес-описание §6; решения владельца 2026-10-21, стенограмма §156): «Просмотрена» —
 * только у новой; статус отклика того же дня заменяет последнюю строку, если она — статус отклика; «Не интересно»
 * всегда добавляется; «Вернуть» — только из «Не интересно», к прежнему статусу (аудит §165).
 */
class StatusRulesTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Test
    void viewedOnlyForNewVacancy() {
        assertThat(StatusRules.change(null, List.of(), StatusEvent.VIEWED, TODAY))
                .isEqualTo(new Change(StatusEvent.VIEWED, null, StatusEvent.VIEWED));
        assertThat(StatusRules.change(StatusEvent.VIEWED, List.of(line(1, StatusEvent.VIEWED, TODAY)),
                StatusEvent.VIEWED, TODAY)).isEqualTo(Change.NONE);
        assertThat(StatusRules.change(StatusEvent.APPLIED, List.of(), StatusEvent.VIEWED, TODAY))
                .isEqualTo(Change.NONE);
    }

    @Test
    void userStatusAfterViewedIsAppendedTheSameDay() {
        assertThat(StatusRules.change(StatusEvent.VIEWED, List.of(line(1, StatusEvent.VIEWED, TODAY)),
                StatusEvent.APPLIED, TODAY)).isEqualTo(new Change(StatusEvent.APPLIED, null, StatusEvent.APPLIED));
    }

    @Test
    void userStatusReplacesUserStatusOfTheSameDay() {
        List<Line> history = List.of(line(1, StatusEvent.VIEWED, TODAY), line(2, StatusEvent.APPLIED, TODAY));

        assertThat(StatusRules.change(StatusEvent.APPLIED, history, StatusEvent.INTERVIEW, TODAY))
                .isEqualTo(new Change(StatusEvent.INTERVIEW, 2L, StatusEvent.INTERVIEW));
    }

    @Test
    void userStatusOfAnotherDayIsAppended() {
        List<Line> history = List.of(line(1, StatusEvent.VIEWED, YESTERDAY), line(2, StatusEvent.APPLIED, YESTERDAY));

        assertThat(StatusRules.change(StatusEvent.APPLIED, history, StatusEvent.INTERVIEW, TODAY))
                .isEqualTo(new Change(StatusEvent.INTERVIEW, null, StatusEvent.INTERVIEW));
    }

    @Test
    void repeatedStatusChangesNothing() {
        assertThat(StatusRules.change(StatusEvent.APPLIED, List.of(line(1, StatusEvent.APPLIED, YESTERDAY)),
                StatusEvent.APPLIED, TODAY)).isEqualTo(Change.NONE);
    }

    @Test
    void returnRestoresStatusBeforeNotInterested() {
        List<Line> history = List.of(line(1, StatusEvent.VIEWED, YESTERDAY), line(2, StatusEvent.APPLIED, YESTERDAY),
                line(3, StatusEvent.NOT_INTERESTED, TODAY));

        assertThat(StatusRules.change(StatusEvent.NOT_INTERESTED, history, StatusEvent.RETURNED, TODAY))
                .isEqualTo(new Change(StatusEvent.RETURNED, null, StatusEvent.APPLIED));
    }

    /** Аудит §165: «Не интересно» в день отклика не стирает отклик — «Вернуть» возвращает в «Мои отклики». */
    @Test
    void notInterestedTheSameDayKeepsResponseForReturn() {
        List<Line> history = List.of(line(1, StatusEvent.VIEWED, TODAY), line(2, StatusEvent.APPLIED, TODAY));

        assertThat(StatusRules.change(StatusEvent.APPLIED, history, StatusEvent.NOT_INTERESTED, TODAY))
                .isEqualTo(new Change(StatusEvent.NOT_INTERESTED, null, StatusEvent.NOT_INTERESTED));
        List<Line> marked = List.of(line(1, StatusEvent.VIEWED, TODAY), line(2, StatusEvent.APPLIED, TODAY),
                line(3, StatusEvent.NOT_INTERESTED, TODAY));
        assertThat(StatusRules.change(StatusEvent.NOT_INTERESTED, marked, StatusEvent.APPLIED, TODAY))
                .isEqualTo(new Change(StatusEvent.APPLIED, null, StatusEvent.APPLIED));
        assertThat(StatusRules.change(StatusEvent.NOT_INTERESTED, marked, StatusEvent.RETURNED, TODAY).current())
                .isEqualTo(StatusEvent.APPLIED);
    }

    @Test
    void returnSkipsEarlierReturns() {
        List<Line> history = List.of(line(1, StatusEvent.INTERVIEW, YESTERDAY),
                line(2, StatusEvent.NOT_INTERESTED, YESTERDAY), line(3, StatusEvent.RETURNED, YESTERDAY),
                line(4, StatusEvent.NOT_INTERESTED, TODAY));

        assertThat(StatusRules.change(StatusEvent.NOT_INTERESTED, history, StatusEvent.RETURNED, TODAY).current())
                .isEqualTo(StatusEvent.INTERVIEW);
    }

    @Test
    void returnWithoutEarlierStatusGivesViewed() {
        assertThat(StatusRules.change(StatusEvent.NOT_INTERESTED, List.of(line(1, StatusEvent.NOT_INTERESTED,
                YESTERDAY)), StatusEvent.RETURNED, TODAY)).isEqualTo(
                        new Change(StatusEvent.RETURNED, null, StatusEvent.VIEWED));
    }

    /** «Вернуть» из «Отклик отправлен» (§200) — снова «Просмотрена»; из собеседования, отказа, просмотренной — ничего. */
    @Test
    void returnFromAppliedGivesViewedOtherwiseNothing() {
        assertThat(StatusRules.change(StatusEvent.APPLIED, List.of(), StatusEvent.RETURNED, TODAY))
                .isEqualTo(new Change(StatusEvent.RETURNED, null, StatusEvent.VIEWED));
        assertThat(StatusRules.change(StatusEvent.INTERVIEW, List.of(), StatusEvent.RETURNED, TODAY))
                .isEqualTo(Change.NONE);
        assertThat(StatusRules.change(StatusEvent.REJECTED, List.of(), StatusEvent.RETURNED, TODAY))
                .isEqualTo(Change.NONE);
        assertThat(StatusRules.change(StatusEvent.VIEWED, List.of(), StatusEvent.RETURNED, TODAY))
                .isEqualTo(Change.NONE);
    }

    @Test
    void statusAfterReturnIsAppended() {
        List<Line> history = List.of(line(1, StatusEvent.NOT_INTERESTED, TODAY), line(2, StatusEvent.RETURNED, TODAY));

        assertThat(StatusRules.change(StatusEvent.VIEWED, history, StatusEvent.NOT_INTERESTED, TODAY))
                .isEqualTo(new Change(StatusEvent.NOT_INTERESTED, null, StatusEvent.NOT_INTERESTED));
    }

    private static Line line(long id, StatusEvent event, LocalDate day) {
        return new Line(id, event, day);
    }
}
