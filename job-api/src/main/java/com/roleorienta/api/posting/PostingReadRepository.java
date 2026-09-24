package com.roleorienta.api.posting;

import com.roleorienta.core.domain.JobPosting;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Доступ на чтение к публикациям для REST-выдачи (§7).
 *
 * <p>Наследует узкий {@link Repository} (а не {@code JpaRepository}): в API-приложении
 * доступны только нужные операции чтения, без методов записи — публикации ведёт
 * {@code job-worker}, а {@code job-api} их только отдаёт.</p>
 */
public interface PostingReadRepository extends Repository<JobPosting, Long> {

    /**
     * Карточка публикации по идентификатору.
     *
     * @param id идентификатор публикации
     * @return публикация или пустое значение, если её нет
     */
    Optional<JobPosting> findById(Long id);

    /**
     * Страница ленты: публикации со строго большим {@code id} (курсор), удовлетворяющие
     * необязательным фильтрам, по возрастанию {@code id}.
     *
     * <p>Фильтр по дате записан как {@code :#{#filter.postedFrom == null} = true or …}: признак
     * «фильтр не задан» вычисляется в Java (булев параметр), а сама дата стоит только в сравнении
     * с {@code postedOn}, откуда Hibernate берёт её тип. Условие {@code :дата is null} не
     * работает: у пустого параметра без контекста типа нет, и PostgreSQL получает его как
     * {@code bytea} или не может определить тип.</p>
     *
     * <p>Каждое поле {@link PostingFilter} применяется только если оно не {@code null}
     * (условие вида «параметр null ИЛИ совпадает»). Курсор — стабильный монотонный
     * {@code id} (ключ {@code IDENTITY}); фильтры — по индексированным нормализованным
     * столбцам (§7.3). Запрашивается на одну строку больше размера страницы (через
     * {@link Limit}) — чтобы определить курсор следующей страницы.</p>
     *
     * @param cursor          {@code id} последней публикации предыдущей страницы (0 — с начала)
     * @param filter          необязательные фильтры (поля {@code null} игнорируются)
     * @param hiddenForUserId id пользователя, чьи скрытые публикации исключить из выборки
     *                        (§31); {@code null} — не исключать (аноним или includeHidden)
     * @param limit           сколько строк вернуть (размер страницы, +1 для определения продолжения)
     * @return публикации по возрастанию {@code id}
     */
    @Query("select p from JobPosting p where p.id > :cursor and " + FILTERS + " order by p.id asc")
    List<JobPosting> search(@Param("cursor") long cursor,
                            @Param("filter") PostingFilter filter,
                            @Param("hiddenForUserId") Long hiddenForUserId,
                            Limit limit);

    /**
     * Страница ленты «свежие первыми» (§70): по убыванию даты публикации, публикации без
     * даты — в конце; при равной дате — по убыванию {@code id}. Фильтры — те же, что у
     * {@link #search}.
     *
     * <p>Пагинация — по ключу (keyset): страница начинается строго после пары
     * (дата, {@code id}) последней публикации предыдущей страницы. Отсутствующая дата
     * заменяется в ключе датой {@link PostedKeyset#NO_DATE} (раньше любой реальной), поэтому
     * публикации без даты идут последними и листаются тем же условием. Первая страница —
     * {@link PostedKeyset#FIRST_PAGE} (позже любой реальной даты).</p>
     *
     * <p>Дата {@code NO_DATE} передаётся через {@code #after.noDate()} (метод записи, а не
     * литерал запроса): значение не {@code null} и стоит в {@code coalesce} рядом с колонкой
     * даты — тип параметра Hibernate берёт из неё (см. оговорку о типе даты у {@link #search}).</p>
     *
     * @param after           ключ последней публикации предыдущей страницы
     * @param filter          необязательные фильтры (поля {@code null} игнорируются)
     * @param hiddenForUserId id пользователя, чьи скрытые публикации исключить, или {@code null}
     * @param limit           сколько строк вернуть (размер страницы, +1 для определения продолжения)
     * @return публикации: свежие первыми, без даты — в конце
     */
    @Query("select p from JobPosting p where "
            + "(coalesce(p.postedOn, :#{#after.noDate()}) < :#{#after.date()}"
            + " or (coalesce(p.postedOn, :#{#after.noDate()}) = :#{#after.date()} and p.id < :#{#after.id()}))"
            + " and " + FILTERS
            + " order by coalesce(p.postedOn, :#{#after.noDate()}) desc, p.id desc")
    List<JobPosting> searchByPosted(@Param("after") PostedKeyset after,
                                    @Param("filter") PostingFilter filter,
                                    @Param("hiddenForUserId") Long hiddenForUserId,
                                    Limit limit);

    /**
     * Условия фильтров ленты (JPQL), общие для {@link #search} и {@link #searchByPosted}.
     * Константа, чтобы одни и те же фильтры не расходились между двумя порядками ленты.
     */
    String FILTERS = """
            (:#{#filter.workModality} is null or p.workModality = :#{#filter.workModality})
              and (:#{#filter.seniority} is null or p.seniority = :#{#filter.seniority})
              and (:#{#filter.country} is null or lower(p.country) = lower(:#{#filter.country})
                   or concat(lower(p.additionalLocations), ';')
                      like concat('%, ', lower(:#{#filter.country}), ';%'))
              and (:#{#filter.minSalary} is null
                   or coalesce(p.salaryMax, p.salaryMin) >= :#{#filter.minSalary})
              and (:#{#filter.postedFrom == null} = true or p.postedOn >= :#{#filter.postedFrom})
              and (:hiddenForUserId is null or not exists (
                      select 1 from SavedPosting sp
                      where sp.posting = p and sp.user.id = :hiddenForUserId
                        and sp.state = com.roleorienta.api.saved.SavedState.HIDDEN))
            """;

    /**
     * Ключ пагинации ленты «свежие первыми»: дата публикации (или {@link #NO_DATE}, если её
     * нет) и {@code id} последней публикации страницы.
     *
     * @param date дата публикации; для публикации без даты — {@link #NO_DATE}
     * @param id   {@code id} публикации
     */
    record PostedKeyset(LocalDate date, long id) {

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
}
