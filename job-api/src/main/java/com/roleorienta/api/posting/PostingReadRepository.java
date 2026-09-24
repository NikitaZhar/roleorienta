package com.roleorienta.api.posting;

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
     * @param hiddenForUserId id пользователя, чьи скрытые публикации исключить из выборки
     *                        (§31); {@code null} — не исключать (аноним или includeHidden)
     * @param limit           сколько строк вернуть (размер страницы, +1 для определения продолжения)
     * @return публикации по возрастанию {@code id}
     */
    @Query("""
            select p from JobPosting p
            where p.id > :cursor
              and (:#{#filter.workModality} is null or p.workModality = :#{#filter.workModality})
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
            order by p.id asc
            """)
    List<JobPosting> search(@Param("cursor") long cursor,
                            @Param("filter") PostingFilter filter,
                            @Param("hiddenForUserId") Long hiddenForUserId,
                            Limit limit);
}
