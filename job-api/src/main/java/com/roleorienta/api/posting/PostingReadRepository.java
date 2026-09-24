package com.roleorienta.api.posting;

import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.core.domain.JobPosting;
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
     * @param coverage        состояние покрытия (A5, §81) или {@code null} — не фильтровать;
     *                        {@code UNKNOWN} включает публикации без оценки
     * @param hiddenForUserId id пользователя, чьи скрытые публикации исключить из выборки
     *                        (§31); {@code null} — не исключать (аноним или includeHidden)
     * @param limit           сколько строк вернуть (размер страницы, +1 для определения продолжения)
     * @return публикации по возрастанию {@code id}
     */
    @Query("select p from JobPosting p where p.id > :cursor and " + FILTERS + " order by p.id asc")
    List<JobPosting> search(@Param("cursor") long cursor,
                            @Param("filter") PostingFilter filter,
                            @Param("coverage") CoverageState coverage,
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
     * @param coverage        состояние покрытия или {@code null} (см. {@link #search})
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
                                    @Param("coverage") CoverageState coverage,
                                    @Param("hiddenForUserId") Long hiddenForUserId,
                                    Limit limit);

    /**
     * Условия фильтров ленты (JPQL), общие для {@link #search} и {@link #searchByPosted}.
     * Покрытие (A5, §81): {@code coverage} задан — только публикации с такой оценкой; для
     * {@code UNKNOWN} — ещё и публикации без оценки (нет записи = не проверено). Признак
     * «запрошено UNKNOWN» вычисляется в Java (SpEL), как у даты.
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
              and (:coverage is null
                   or exists (select 1 from CoverageAssessment ca
                              where ca.posting = p and ca.state = :coverage)
                   or (:#{#coverage != null and #coverage.name() == 'UNKNOWN'} = true
                       and not exists (select 1 from CoverageAssessment cu where cu.posting = p)))
              and (:hiddenForUserId is null or not exists (
                      select 1 from SavedPosting sp
                      where sp.posting = p and sp.user.id = :hiddenForUserId
                        and sp.state = com.roleorienta.api.saved.SavedState.HIDDEN))
            """;
}
