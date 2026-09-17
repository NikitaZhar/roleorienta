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
     * <p>Каждое поле {@link PostingFilter} применяется только если оно не {@code null}
     * (условие вида «параметр null ИЛИ совпадает»). Курсор — стабильный монотонный
     * {@code id} (ключ {@code IDENTITY}); фильтры — по индексированным нормализованным
     * столбцам (§7.3). Запрашивается на одну строку больше размера страницы (через
     * {@link Limit}) — чтобы определить курсор следующей страницы.</p>
     *
     * @param cursor {@code id} последней публикации предыдущей страницы (0 — с начала)
     * @param filter необязательные фильтры (поля {@code null} игнорируются)
     * @param limit  сколько строк вернуть (размер страницы, +1 для определения продолжения)
     * @return публикации по возрастанию {@code id}
     */
    @Query("""
            select p from JobPosting p
            where p.id > :cursor
              and (:#{#filter.workModality} is null or p.workModality = :#{#filter.workModality})
              and (:#{#filter.seniority} is null or p.seniority = :#{#filter.seniority})
              and (:#{#filter.country} is null or lower(p.country) = lower(:#{#filter.country}))
              and (:#{#filter.minSalary} is null or p.salaryMax >= :#{#filter.minSalary})
            order by p.id asc
            """)
    List<JobPosting> search(@Param("cursor") long cursor,
                            @Param("filter") PostingFilter filter,
                            Limit limit);
}
