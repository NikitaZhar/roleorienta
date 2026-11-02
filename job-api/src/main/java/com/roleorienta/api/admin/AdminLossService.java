package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.Loss;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Потери вакансий (§235): следующая компания для администратора — по одной, «отработал — следующая» (решение
 * владельца 2026-11-02) — и действия по ней.
 */
@Service
public class AdminLossService {

    /** «Later»: компания снова предлагается через столько — как перепроверка сайтов ({@code app.career.recheck-after}). */
    static final Duration POSTPONE = Duration.ofDays(10);

    private final AdminLossRepository losses;

    /**
     * @param losses очередь потерь и действия
     */
    public AdminLossService(AdminLossRepository losses) {
        this.losses = losses;
    }

    /**
     * @return следующая компания с потерями, её сайты и до 5 вакансий, которых нет на её странице; пусто — потерь нет
     */
    @Transactional(readOnly = true)
    public Optional<Loss> next() {
        return losses.next().map(company -> new Loss(company, losses.sites(company.id()), losses.missing(company.id())));
    }

    /**
     * @param companyId компания
     * @return {@code false} — действующей компании нет
     */
    @Transactional
    public boolean postpone(long companyId) {
        return losses.postpone(companyId, POSTPONE);
    }

    /**
     * @param companyId компания
     * @param noOwnPage своей кадровой страницы нет
     * @return {@code false} — действующей компании нет
     */
    @Transactional
    public boolean markNoOwnCareerPage(long companyId, boolean noOwnPage) {
        return losses.markNoOwnCareerPage(companyId, noOwnPage);
    }
}
