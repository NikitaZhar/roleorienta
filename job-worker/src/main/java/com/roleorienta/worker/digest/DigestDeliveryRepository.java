package com.roleorienta.worker.digest;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доставки дайджеста (A7, §88). */
public interface DigestDeliveryRepository extends JpaRepository<DigestDelivery, Long> {

    /**
     * Письма, ждущие отправки или повтора, в порядке заведения.
     *
     * @param state {@link DigestDeliveryState#PENDING}
     * @param limit бюджет прохода
     * @return доставки
     */
    List<DigestDelivery> findByStateOrderById(DigestDeliveryState state, Limit limit);
}
