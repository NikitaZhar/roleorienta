package com.roleorienta.api.posting;

import com.roleorienta.core.domain.JobPosting;
import java.time.LocalDate;

/**
 * Ключ пагинации ленты «свежие первыми» (§70): дата публикации (или {@link #NO_DATE}, если её
 * нет) и {@code id} последней публикации страницы, а также упаковка ключа в курсор API.
 * Вынесен из {@link PostingReadRepository} при аудите (§76): в репозитории — только запросы,
 * логика курсора — здесь (контракт §3.3).
 *
 * @param date дата публикации; для публикации без даты — {@link #NO_DATE}
 * @param id   {@code id} публикации
 */
public record PostedKeyset(LocalDate date, long id) {

    /** Замена отсутствующей даты в ключе: раньше любой реальной даты публикации. */
    public static final LocalDate NO_DATE = LocalDate.of(1900, 1, 1);

    /** Ключ первой страницы: позже любой реальной даты, {@code id} — максимальный. */
    public static final PostedKeyset FIRST_PAGE = new PostedKeyset(LocalDate.of(9999, 12, 31), Long.MAX_VALUE);

    /**
     * Сколько младших бит курсора занимает {@code id} (до ~2,1 млрд публикаций). Вместе с
     * 22 битами дня курсор укладывается в 53 бита — JavaScript-клиент читает число из
     * JSON без потери точности.
     */
    private static final int ID_BITS = 31;
    private static final long ID_MASK = (1L << ID_BITS) - 1;
    /** Сдвиг номера дня, чтобы {@link #NO_DATE} (день до 1970 года) давал неотрицательный курсор. */
    private static final long DAY_OFFSET = 100_000;

    /** @return {@link #NO_DATE} — для выражения запроса ({@code #after.noDate()}) */
    public LocalDate noDate() {
        return NO_DATE;
    }

    /**
     * Ключ публикации: её дата (или {@link #NO_DATE}) и {@code id}.
     *
     * @param posting публикация — последняя на странице
     * @return ключ для следующей страницы
     */
    public static PostedKeyset of(JobPosting posting) {
        LocalDate posted = posting.getPostedOn();
        return new PostedKeyset(posted == null ? NO_DATE : posted, posting.getId());
    }

    /**
     * Курсор API — одно число, как и у ленты по {@code id} (формат {@code nextCursor} не
     * меняется): старшие биты — номер дня даты со сдвигом {@link #DAY_OFFSET}, младшие
     * {@link #ID_BITS} бит — {@code id}. Клиент передаёт его обратно как есть.
     *
     * @return курсор для {@code nextCursor}
     */
    public long toCursor() {
        return ((date.toEpochDay() + DAY_OFFSET) << ID_BITS) | id;
    }

    /**
     * Ключ из курсора API; {@code null} — первая страница.
     *
     * @param cursor курсор из запроса или {@code null}
     * @return ключ пагинации
     */
    public static PostedKeyset fromCursor(Long cursor) {
        if (cursor == null) {
            return FIRST_PAGE;
        }
        return new PostedKeyset(LocalDate.ofEpochDay((cursor >>> ID_BITS) - DAY_OFFSET), cursor & ID_MASK);
    }
}
