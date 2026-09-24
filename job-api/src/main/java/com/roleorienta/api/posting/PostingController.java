package com.roleorienta.api.posting;

import com.roleorienta.api.posting.PostingDtos.Card;
import com.roleorienta.api.posting.PostingDtos.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST-эндпоинты чтения публикаций (§7): лента и карточка.
 *
 * <p>Контроллер тонкий (контракт §3.3): принимает параметры и делегирует
 * {@link PostingQueryService}, без бизнес-логики. Путь версионирован ({@code /api/v1}).
 * Ресурс назван {@code postings} — это исходная публикация ({@code JobPosting});
 * агрегированные {@code /vacancies} (§7) появятся, когда будет модель {@code Vacancy}.
 * Отсутствие карточки → {@code 404}; тело ошибки — {@code application/problem+json}
 * (RFC 9457, включено {@code spring.mvc.problemdetails}).</p>
 *
 * <p>Лента открыта и для анонимных, и для вошедших ({@code GET /api/v1/postings/**}
 * разрешён без входа). Для вошедшего лента персонализируется (§31): скрытые им публикации
 * исключаются (если не задан {@code includeHidden=true}), а элементы помечаются его
 * отношением. {@link Authentication} для анонимного запроса равен {@code null}.</p>
 */
@RestController
@RequestMapping("/api/v1/postings")
public class PostingController {

    private final PostingQueryService postingQueryService;

    /**
     * @param postingQueryService чтение публикаций
     */
    public PostingController(PostingQueryService postingQueryService) {
        this.postingQueryService = postingQueryService;
    }

    /**
     * Лента публикаций с курсорной пагинацией.
     *
     * @param paging         курсор, размер страницы и порядок ({@code sort=ID|POSTED}); все
     *                       поля необязательны, берутся из query-параметров
     * @param includeHidden  для вошедшего: включать ли скрытые им публикации (по умолчанию нет)
     * @param filter         необязательные фильтры ленты (§7.3); поля берутся из query-параметров
     * @param authentication текущий пользователь или {@code null} (аноним)
     * @return страница ленты и курсор следующей
     */
    @GetMapping
    public Page list(
            FeedPaging paging,
            @RequestParam(required = false, defaultValue = "false") boolean includeHidden,
            PostingFilter filter,
            Authentication authentication) {
        return postingQueryService.list(paging, filter, authentication, includeHidden);
    }

    /**
     * Карточка публикации по идентификатору.
     *
     * @param id идентификатор публикации
     * @return карточка публикации
     * @throws ResponseStatusException {@code 404}, если публикации нет
     */
    @GetMapping("/{id}")
    public Card get(@PathVariable Long id) {
        return postingQueryService.card(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Публикация не найдена: " + id));
    }
}
