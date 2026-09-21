package com.roleorienta.worker.discovery;

import com.roleorienta.core.domain.Provider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Провайдеры: при авто-подключении кандидата провайдер находится по коду или заводится. */
public interface ProviderRepository extends JpaRepository<Provider, Long> {

    Optional<Provider> findByCode(String code);
}
