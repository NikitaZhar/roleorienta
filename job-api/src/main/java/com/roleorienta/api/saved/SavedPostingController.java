package com.roleorienta.api.saved;

import com.roleorienta.api.saved.SavedPostingDtos.HideRequest;
import com.roleorienta.api.saved.SavedPostingDtos.SavedPostingResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-эндпоинты персональных маркеров публикаций (§7): сохранить, скрыть, отметить
 * просмотренной, снять и список сохранённых текущего пользователя.
 *
 * <p>Контроллер тонкий (§3.3): передаёт {@link Authentication} и параметры в
 * {@link SavedPostingService}, без бизнес-логики. Пути версионированы ({@code /api/v1}).
 * Ресурс — {@code postings} (исходная {@code JobPosting}); агрегированные {@code /vacancies}
 * появятся с моделью {@code Vacancy} (§26.2).</p>
 *
 * <p><b>Доступ и почему {@code SecurityConfig} не меняется (§4).</b> Пишущие операции —
 * {@code POST}/{@code DELETE} под {@code /api/v1/postings/**}; открыт же только
 * {@code GET /api/v1/postings/**}, поэтому они уже требуют аутентификации по правилу
 * {@code anyRequest().authenticated()}. Персональный список вынесен под
 * {@code /api/v1/me/saved-postings} — вне публичного {@code GET}-матчера. Аннотация и
 * фильтрация самой ленты (§31) сделаны в открытом {@code GET /api/v1/postings}: аноним
 * получает ленту как раньше, вошедший — со скрытием и пометками.</p>
 */
@RestController
public class SavedPostingController {

    private final SavedPostingService savedPostingService;

    /**
     * @param savedPostingService логика маркеров
     */
    public SavedPostingController(SavedPostingService savedPostingService) {
        this.savedPostingService = savedPostingService;
    }

    /**
     * Пометить публикацию сохранённой.
     *
     * @param id             id публикации
     * @param authentication текущий пользователь
     * @return маркер в состоянии {@code SAVED}
     */
    @PostMapping("/api/v1/postings/{id}/save")
    public SavedPostingResponse save(@PathVariable Long id, Authentication authentication) {
        return savedPostingService.save(authentication, id);
    }

    /**
     * Скрыть публикацию (с необязательной причиной в теле запроса).
     *
     * @param id             id публикации
     * @param request        тело с причиной (необязательно; может отсутствовать)
     * @param authentication текущий пользователь
     * @return маркер в состоянии {@code HIDDEN}
     */
    @PostMapping("/api/v1/postings/{id}/hide")
    public SavedPostingResponse hide(@PathVariable Long id,
                                     @Valid @RequestBody(required = false) HideRequest request,
                                     Authentication authentication) {
        String reason = request == null ? null : request.reason();
        return savedPostingService.hide(authentication, id, reason);
    }

    /**
     * Отметить публикацию просмотренной.
     *
     * @param id             id публикации
     * @param authentication текущий пользователь
     * @return маркер с проставленным {@code seenAt}
     */
    @PostMapping("/api/v1/postings/{id}/seen")
    public SavedPostingResponse seen(@PathVariable Long id, Authentication authentication) {
        return savedPostingService.markSeen(authentication, id);
    }

    /**
     * Снять сохранение/скрытие (просмотр сохраняется).
     *
     * @param id             id публикации
     * @param authentication текущий пользователь
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/api/v1/postings/{id}/saved")
    public ResponseEntity<Void> remove(@PathVariable Long id, Authentication authentication) {
        savedPostingService.remove(authentication, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Сохранённые публикации текущего пользователя.
     *
     * @param authentication текущий пользователь
     * @return список маркеров в состоянии {@code SAVED}
     */
    @GetMapping("/api/v1/me/saved-postings")
    public List<SavedPostingResponse> listSaved(Authentication authentication) {
        return savedPostingService.listSaved(authentication);
    }
}
