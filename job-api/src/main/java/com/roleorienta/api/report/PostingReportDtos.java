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
    public record ReportResponse(Long id, Long postingId, String reason, String status,
                                 String comment, Instant createdAt) {

        static ReportResponse of(PostingReport report) {
            return new ReportResponse(
                    report.getId(),
                    report.getPosting().getId(),
                    report.getReason().name(),
                    report.getStatus().name(),
                    report.getComment(),
                    report.getCreatedAt());
        }
    }

    /** Жалоба в админ-очереди разбора (§47): с автором для контекста. */
    public record AdminReportResponse(Long id, Long postingId, String reporterEmail, String reason,
                                      String status, String comment, Instant createdAt) {

        static AdminReportResponse of(PostingReport report) {
            return new AdminReportResponse(
                    report.getId(),
                    report.getPosting().getId(),
                    report.getReporter().getEmail(),
                    report.getReason().name(),
                    report.getStatus().name(),
                    report.getComment(),
                    report.getCreatedAt());
        }
    }
}
