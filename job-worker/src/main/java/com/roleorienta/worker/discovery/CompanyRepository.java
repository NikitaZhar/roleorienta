package com.roleorienta.worker.discovery;

import com.roleorienta.core.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

/** Компании: создаются при авто-подключении уверенного кандидата. */
public interface CompanyRepository extends JpaRepository<Company, Long> {
}
