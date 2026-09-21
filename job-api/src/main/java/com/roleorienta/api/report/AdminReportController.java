package com.roleorienta.api.report;

import com.roleorienta.api.report.PostingReportDtos.AdminReportResponse;
import com.roleorienta.api.report.PostingReportDtos.TriageReportRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Админ-эндпоинты разбора жалоб на публикации (§47).
 *
 * <p>Контроллер тонкий (§3.3): делегирует {@link PostingReportService}. Пути под
 * {@code /api/v1/admin/**} уже требуют роли {@code ADMIN} ({@code SecurityConfig}) —
 * правки безопасности не нужны; {@code PATCH} несёт CSRF-токен.</p>
 */
@RestController
public class AdminReportController {

    private final PostingReportService service;

    public AdminReportController(PostingReportService service) {
        this.service = service;
    }

    /** Очередь жалоб; при {@code ?status=OPEN} — только заданного статуса. */
    @GetMapping("/api/v1/admin/reports")
    public List<AdminReportResponse> list(
            @RequestParam(value = "status", required = false) PostingReportStatus status) {
        return service.listForModeration(status);
    }

    /** Разобрать жалобу: перевести в {@code RESOLVED} или {@code DISMISSED}. */
    @PatchMapping("/api/v1/admin/reports/{reportId}")
    public AdminReportResponse triage(@PathVariable Long reportId,
                                      @Valid @RequestBody TriageReportRequest request) {
        return service.triage(reportId, request.status());
    }
}
