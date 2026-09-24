package com.roleorienta.worker.discovery;

import com.roleorienta.core.domain.CompanySource;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Связь «источник → работодатель»: создаётся при авто-подключении (verifiedBy=AUTO). */
public interface CompanySourceRepository extends JpaRepository<CompanySource, Long> {

    /**
     * Связи компаний, у которых имя — slug доски ({@code lilly/LLY}): заведены до §80, когда имя
     * ещё не бралось из описания доски. Компания, источник и провайдер — одним запросом.
     *
     * @param limit бюджет прохода
     * @return связи по возрастанию {@code id} компании
     */
    @Query("""
            select cs from CompanySource cs join fetch cs.company c join fetch cs.source s join fetch s.provider
            where c.name like '%/%' order by c.id, s.id
            """)
    List<CompanySource> withSlugNames(Limit limit);
}
