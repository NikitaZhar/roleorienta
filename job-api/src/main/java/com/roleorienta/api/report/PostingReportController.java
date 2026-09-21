package com.roleorienta.api.report;

import com.roleorienta.api.report.PostingReportDtos.CreateReportRequest;
import com.roleorienta.api.report.PostingReportDtos.ReportResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-эндпоинты жалоб на публикации (§46) — «сообщить об ошибке».
 *
 * <p>Контроллер тонкий (§3.3): делегирует {@link PostingReportService}. {@code POST} на публикацию
 * требует входа и CSRF (под {@code /api/v1/postings/**} открыт только {@code GET}); список жалоб
 * приватен автору (A23). {@code SecurityConfig} не меняется.</p>
 */
@RestController
public class PostingReportController {

    private final PostingReportService service;

    public PostingReportController(PostingReportService service) {
        this.service = service;
    }

    /** Пожаловаться на публикацию (идемпотентно). */
    @PostMapping("/api/v1/postings/{postingId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse create(@PathVariable Long postingId,
                                 @Valid @RequestBody CreateReportRequest request,
                                 Authentication authentication) {
        return service.create(authentication, postingId, request.reason(), request.comment());
    }

    /** Жалобы текущего пользователя. */
    @GetMapping("/api/v1/reports")
    public List<ReportResponse> list(Authentication authentication) {
        return service.list(authentication);
    }
}
