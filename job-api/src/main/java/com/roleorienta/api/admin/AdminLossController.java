package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.Loss;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * «Losses» на /admin (§235): компании, у которых вакансии портала и profesia.sk не найдены на собственной кадровой
 * странице, — по одной, с уведомлением браузера. Доступ к {@code /api/v1/admin/**} — только роль {@code ADMIN}
 * ({@code SecurityConfig}).
 */
@RestController
public class AdminLossController {

    private static final String COMPANY_NOT_FOUND = "Company not found";

    private final AdminLossService losses;

    /**
     * @param losses очередь потерь и действия
     */
    public AdminLossController(AdminLossService losses) {
        this.losses = losses;
    }

    /**
     * @return {@code 200} — следующая компания с потерями; {@code 204} — потерь нет
     */
    @GetMapping("/api/v1/admin/losses/next")
    public ResponseEntity<Loss> next() {
        return losses.next().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * «Later»: компания не предлагается 10 дней.
     *
     * @param id компания
     * @return {@code 204}; {@code 404} — компании нет
     */
    @PostMapping("/api/v1/admin/companies/{id}/loss-postponed")
    public ResponseEntity<Void> postpone(@PathVariable long id) {
        return done(losses.postpone(id));
    }

    /**
     * «No own career page»: вакансии компании — с портала и profesia.sk, в потери она не попадает.
     *
     * @param id компания
     * @return {@code 204}; {@code 404} — компании нет
     */
    @PutMapping("/api/v1/admin/companies/{id}/no-own-career-page")
    public ResponseEntity<Void> markNoOwnCareerPage(@PathVariable long id) {
        return done(losses.markNoOwnCareerPage(id, true));
    }

    /**
     * @param id компания
     * @return {@code 204}; {@code 404} — компании нет
     */
    @DeleteMapping("/api/v1/admin/companies/{id}/no-own-career-page")
    public ResponseEntity<Void> unmarkNoOwnCareerPage(@PathVariable long id) {
        return done(losses.markNoOwnCareerPage(id, false));
    }

    private static ResponseEntity<Void> done(boolean found) {
        if (!found) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, COMPANY_NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }
}
