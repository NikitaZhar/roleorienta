package com.roleorienta.api.subscription;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к подпискам на компании — всегда в пределах владельца (A23). */
public interface CompanySubscriptionRepository extends JpaRepository<CompanySubscription, Long> {

    Optional<CompanySubscription> findByUser_IdAndCompany_Id(Long userId, Long companyId);

    List<CompanySubscription> findByUser_IdOrderByIdDesc(Long userId);
}
