package com.roleorienta.api.report;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Тела запросов и ответов эндпоинтов жалоб на публикации (§46). */
public final class PostingReportDtos {

    private PostingReportDtos() {
    }

    /** Пожаловаться на публикацию: причина и необязательный комментарий. */
    public record CreateReportRequest(@NotNull PostingReportReason reason,
                                      @Size(max = 2000) String comment) {
    }

    /** Перевести жалобу в терминальный статус (разбор, §47). */
    public record TriageReportRequest(@NotNull PostingReportStatus status) {
    }

    /** Жалоба в ответе. */
    public record ReportResponse(Long id, Long postingId, Complaint complaint, String status, Instant createdAt) {

        static ReportResponse of(PostingReport report) {
            return new ReportResponse(
                    report.getId(),
                    report.getPosting().getId(),
                    new Complaint(report.getReason().name(), report.getComment()),
                    report.getStatus().name(),
                    report.getCreatedAt());
        }
    }

    /**
     * Суть жалобы (§78: поля сгруппированы).
     *
     * @param reason  типовая причина
     * @param comment комментарий автора или {@code null}
     */
    public record Complaint(String reason, String comment) {
    }

    /**
     * Жалоба в админ-очереди разбора (§47): жалоба и её автор для контекста.
     *
     * @param report        жалоба
     * @param reporterEmail email автора
     */
    public record AdminReportResponse(ReportResponse report, String reporterEmail) {

        static AdminReportResponse of(PostingReport report) {
            return new AdminReportResponse(ReportResponse.of(report), report.getReporter().getEmail());
        }
    }
}
