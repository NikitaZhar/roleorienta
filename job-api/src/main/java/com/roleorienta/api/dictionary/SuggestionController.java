package com.roleorienta.api.dictionary;

import com.roleorienta.api.account.AccountService;
import com.roleorienta.api.dictionary.SuggestionDtos.AdminSuggestionItem;
import com.roleorienta.api.dictionary.SuggestionDtos.DecisionRequest;
import com.roleorienta.api.dictionary.SuggestionDtos.SuggestionItem;
import com.roleorienta.api.dictionary.SuggestionDtos.SuggestionRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Предложения позиций: свои — у вошедшего пользователя, все и решение — у администратора (технический документ §8;
 * стенограмма §161). {@code /api/v1/admin/**} — только роль {@code ADMIN} ({@code SecurityConfig}).
 */
@RestController
public class SuggestionController {

    private final SuggestionService suggestions;
    private final AccountService accounts;

    /**
     * @param suggestions предложения
     * @param accounts    учётные записи
     */
    public SuggestionController(SuggestionService suggestions, AccountService accounts) {
        this.suggestions = suggestions;
        this.accounts = accounts;
    }

    /**
     * @param authentication вошедший пользователь
     * @param request        название позиции и примеры названий вакансий
     * @return предложение на рассмотрении, {@code 201}; {@code 400} — пусто или длиннее 500 символов; {@code 429} —
     *         больше {@value SuggestionService#DAILY_LIMIT} за сутки
     */
    @PostMapping("/api/v1/me/position-suggestions")
    @ResponseStatus(HttpStatus.CREATED)
    public SuggestionItem suggest(Authentication authentication, @Valid @RequestBody SuggestionRequest request) {
        return suggestions.suggest(userId(authentication), request.text())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "No more than " + SuggestionService.DAILY_LIMIT + " suggestions a day"));
    }

    /**
     * @param authentication вошедший пользователь
     * @return свои предложения, новые сверху
     */
    @GetMapping("/api/v1/me/position-suggestions")
    public List<SuggestionItem> own(Authentication authentication) {
        return suggestions.own(userId(authentication));
    }

    /**
     * @return все предложения: на рассмотрении — первыми, с почтой пользователя
     */
    @GetMapping("/api/v1/admin/position-suggestions")
    public List<AdminSuggestionItem> all() {
        return suggestions.all();
    }

    /**
     * @param id       предложение
     * @param decision «добавлена» с кодом позиции или «отклонена» с необязательной причиной
     * @return {@code 204}; {@code 400} — неверное решение или неизвестная позиция; {@code 404} — предложения нет
     */
    @PutMapping("/api/v1/admin/position-suggestions/{id}")
    public ResponseEntity<Void> decide(@PathVariable long id, @Valid @RequestBody DecisionRequest decision) {
        if (!suggestions.decide(id, decision)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Suggestion not found");
        }
        return ResponseEntity.noContent().build();
    }

    private long userId(Authentication authentication) {
        return accounts.byEmail(authentication.getName()).getId();
    }
}
