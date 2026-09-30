package com.roleorienta.api.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к учётным записям.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * @param email email в нижнем регистре
     * @return учётная запись
     */
    Optional<UserAccount> findByEmail(String email);

    /**
     * @param email email в нижнем регистре
     * @return есть ли учётная запись
     */
    boolean existsByEmail(String email);
}
