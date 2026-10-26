package com.roleorienta.api.condition;

import com.roleorienta.api.account.AccountService;
import com.roleorienta.api.condition.ConditionDtos.CatalogItem;
import com.roleorienta.api.condition.ConditionDtos.ConditionRequest;
import com.roleorienta.api.condition.ConditionDtos.ConditionResponse;
import com.roleorienta.api.condition.ConditionDtos.Direction;
import com.roleorienta.api.condition.ConditionDtos.PositionOption;
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
    private final OccupationRepository occupations;
    private final ApiProperties properties;

    /**
     * @param conditions  условия поиска
     * @param accounts    учётные записи
     * @param occupations выбор позиций и групп
     * @param properties  поддерживаемые страны
     */
    public ConditionController(ConditionService conditions, AccountService accounts, OccupationRepository occupations,
            ApiProperties properties) {
        this.conditions = conditions;
        this.accounts = accounts;
        this.occupations = occupations;
        this.properties = properties;
    }

    /**
     * @param authentication вошедший пользователь
     * @return условия и {@code ETag} версии (названия групп — на языке {@code Accept-Language}); {@code 404} — условия
     *         не заданы
     */
    @GetMapping("/api/v1/me/search-condition")
    public ResponseEntity<ConditionResponse> get(Authentication authentication) {
        ConditionService.Saved saved = conditions.current(userId(authentication), OccupationRepository.language())
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
        ConditionService.Saved saved = conditions.save(userId(authentication), request, ifMatch,
                OccupationRepository.language());
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
     * @param query часть названия (у позиции — и кода; у группы — и синонима ESCO); пусто — первые по порядку
     * @return подсказка при вводе (§204): позиции словаря, затем группы ISCO-08 (названия — на языке
     *         {@code Accept-Language}); не больше 20
     */
    @GetMapping("/api/v1/positions")
    public List<PositionOption> positions(@RequestParam(defaultValue = "") String query) {
        return occupations.suggestions(query, OccupationRepository.language());
    }

    /**
     * @return окно «Все позиции» (§204): направления → группы → позиции словаря (названия — на языке
     *         {@code Accept-Language})
     */
    @GetMapping("/api/v1/occupations")
    public List<Direction> occupations() {
        return occupations.catalog(OccupationRepository.language());
    }

    private long userId(Authentication authentication) {
        return accounts.byEmail(authentication.getName()).getId();
    }
}
