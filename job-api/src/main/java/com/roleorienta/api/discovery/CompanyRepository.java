package com.roleorienta.api.discovery;

import com.roleorienta.core.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

/** Компании: создаются при подтверждении кандидата. */
public interface CompanyRepository extends JpaRepository<Company, Long> {
}
