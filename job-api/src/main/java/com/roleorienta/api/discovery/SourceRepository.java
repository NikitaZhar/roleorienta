package com.roleorienta.api.discovery;

import com.roleorienta.core.domain.Source;
import org.springframework.data.jpa.repository.JpaRepository;

/** Источники сбора: заводятся при подтверждении кандидата и ставятся на сбор (state=ACTIVE). */
public interface SourceRepository extends JpaRepository<Source, Long> {
}
