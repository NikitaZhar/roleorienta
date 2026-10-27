package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.UnlinkedSource;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * «Sources without company» на /admin (§206): источники вакансий без компании и их закрытие. Доступ к
 * {@code /api/v1/admin/**} — только роль {@code ADMIN} ({@code SecurityConfig}).
 */
@RestController
public class AdminSourceController {

    private final AdminSourceService sources;

    /**
     * @param sources источники без компании
     */
    public AdminSourceController(AdminSourceService sources) {
        this.sources = sources;
    }

    /**
     * @return незакрытые источники без компании с открытыми публикациями, больше публикаций — первыми (до 100)
     */
    @GetMapping("/api/v1/admin/sources/unlinked")
    public List<UnlinkedSource> unlinked() {
        return sources.unlinked();
    }

    /**
     * Закрыть источник: не читается, его открытые публикации и вакансии без других публикаций закрываются.
     *
     * @param id источник
     * @return {@code 204}; {@code 404} — нет такого незакрытого источника без компании
     */
    @PostMapping("/api/v1/admin/sources/{id}/close")
    public ResponseEntity<Void> close(@PathVariable long id) {
        if (!sources.close(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Open source without company not found");
        }
        return ResponseEntity.noContent().build();
    }
}
