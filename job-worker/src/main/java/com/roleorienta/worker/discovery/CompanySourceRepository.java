package com.roleorienta.worker.discovery;

import com.roleorienta.core.domain.CompanySource;
import org.springframework.data.jpa.repository.JpaRepository;

/** Связь «источник → работодатель»: создаётся при авто-подключении (verifiedBy=AUTO). */
public interface CompanySourceRepository extends JpaRepository<CompanySource, Long> {
}
