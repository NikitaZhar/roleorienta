package com.roleorienta.api.condition;

import com.roleorienta.api.account.AccountService;
import com.roleorienta.api.condition.ConditionDtos.CatalogItem;
import com.roleorienta.api.condition.ConditionDtos.ConditionRequest;
import com.roleorienta.api.condition.ConditionDtos.ConditionResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Условия поиска вошедшего пользователя и справочники для их выбора (технический документ §8).
 * Условия — только свои: пользователь берётся из сессии.
 */
@RestController
public class ConditionController {

    private final ConditionService conditions;
    private final AccountService accounts;
    private final PositionRepository positions;
    private final ApiProperties properties;

    /**
     * @param conditions условия поиска
     * @param accounts   учётные записи
     * @param positions  позиции словаря
     * @param properties поддерживаемые страны
     */
    public ConditionController(ConditionService conditions, AccountService accounts, PositionRepository positions,
            ApiProperties properties) {
        this.conditions = conditions;
        this.accounts = accounts;
        this.positions = positions;
        this.properties = properties;
    }

    /**
     * @param authentication вошедший пользователь
     * @return условия и {@code ETag} версии; {@code 404} — условия не заданы
     */
    @GetMapping("/api/v1/me/search-condition")
    public ResponseEntity<ConditionResponse> get(Authentication authentication) {
        ConditionService.Saved saved = conditions.current(userId(authentication))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search condition is not set"));
        return ResponseEntity.ok().eTag(saved.etag()).body(saved.condition());
    }

    /**
     * @param authentication вошедший пользователь
     * @param request        условия
     * @param ifMatch        {@code ETag} текущей версии; первое сохранение — без него
     * @return сохранённые условия и {@code ETag} версии; {@code 412}/{@code 428} — версия; {@code 400} — поля
     */
    @PutMapping("/api/v1/me/search-condition")
    public ResponseEntity<ConditionResponse> put(Authentication authentication,
            @Valid @RequestBody ConditionRequest request,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ConditionService.Saved saved = conditions.save(userId(authentication), request, ifMatch);
        return ResponseEntity.ok().eTag(saved.etag()).body(saved.condition());
    }

    /**
     * @return поддерживаемые страны: код и название по-английски
     */
    @GetMapping("/api/v1/countries")
    public List<CatalogItem> countries() {
        return properties.countries().stream()
                .map(code -> new CatalogItem(code, Locale.of("", code).getDisplayCountry(Locale.ENGLISH))).toList();
    }

    /**
     * @param query часть названия или кода; пусто — все (не больше 20)
     * @return позиции словаря
     */
    @GetMapping("/api/v1/positions")
    public List<CatalogItem> positions(@RequestParam(defaultValue = "") String query) {
        return positions.findTop20ByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrderByName(query, query)
                .stream().map(position -> new CatalogItem(position.getCode(), position.getName())).toList();
    }

    private long userId(Authentication authentication) {
        return accounts.byEmail(authentication.getName()).getId();
    }
}
