package com.roleorienta.api.application;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к откликам — всегда в пределах владельца (A23). */
public interface ApplicationRepository extends JpaRepository<Application, Long> {

    Optional<Application> findByUser_IdAndPosting_Id(Long userId, Long postingId);

    List<Application> findByUser_IdOrderByIdDesc(Long userId);

    Optional<Application> findByIdAndUser_Id(Long id, Long userId);
}
