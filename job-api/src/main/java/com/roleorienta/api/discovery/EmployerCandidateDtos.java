package com.roleorienta.api.discovery;

import com.roleorienta.core.domain.EmployerCandidate;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/** Тела запросов и ответов админ-эндпоинтов очереди кандидатов (§7, §33). */
public final class EmployerCandidateDtos {

    private EmployerCandidateDtos() {
    }

    /** Запуск обнаружения по явному кандидату (ручной вход, §5). */
    public record DiscoverRequest(
            @NotBlank String providerCode,
            @NotBlank String slug,
            @NotBlank String baseUrl) {
    }

    /** Подтверждение кандидата; имя компании необязательно (по умолчанию — slug). */
    public record ConfirmRequest(String companyName) {
    }

    /** Представление кандидата в очереди. */
    public record CandidateResponse(
            Long id,
            String providerCode,
            String slug,
            String baseUrl,
            String state,
            String confidence,
            String reason,
            int postingCount,
            Long companyId,
            Long sourceId,
            Instant createdAt) {

        static CandidateResponse of(EmployerCandidate c) {
            return new CandidateResponse(
                    c.getId(),
                    c.getProviderCode(),
                    c.getSlug(),
                    c.getBaseUrl(),
                    c.getState().name(),
                    c.getConfidence().name(),
                    c.getReason(),
                    c.getPostingCount(),
                    c.getCompanyId(),
                    c.getSourceId(),
                    c.getCreatedAt());
        }
    }
}
