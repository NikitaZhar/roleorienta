package com.roleorienta.api.discovery;

import com.roleorienta.core.domain.Company;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Компании: создаются при подтверждении кандидата. */
public interface CompanyRepository extends JpaRepository<Company, Long> {

    /**
     * Компания по ключу идентичности (A3, §80).
     *
     * @param identityKey ключ {@link Company#identityKey}
     * @return компания или пусто
     */
    Optional<Company> findByIdentityKey(String identityKey);
}
