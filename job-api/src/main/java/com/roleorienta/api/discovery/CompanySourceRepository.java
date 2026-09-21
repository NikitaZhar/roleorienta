package com.roleorienta.api.discovery;

import com.roleorienta.core.domain.CompanySource;
import org.springframework.data.jpa.repository.JpaRepository;

/** Связь «источник → работодатель»: создаётся при подтверждении кандидата. */
public interface CompanySourceRepository extends JpaRepository<CompanySource, Long> {
}
