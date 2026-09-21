package com.roleorienta.api.discovery;

import com.roleorienta.core.domain.Provider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Провайдеры: при подтверждении кандидата провайдер находится по коду или заводится. */
public interface ProviderRepository extends JpaRepository<Provider, Long> {

    Optional<Provider> findByCode(String code);
}
