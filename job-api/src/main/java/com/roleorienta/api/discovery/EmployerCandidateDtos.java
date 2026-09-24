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

    /**
     * Доска кандидата: где его лента.
     *
     * @param providerCode провайдер (напр. {@code workday})
     * @param slug         доска у провайдера (у Workday — {@code tenant/site})
     * @param baseUrl      базовый адрес ленты
     */
    public record Board(String providerCode, String slug, String baseUrl) {
    }

    /**
     * Итог проверки ленты (гейт уверенности).
     *
     * @param state        состояние кандидата ({@code PENDING}, {@code CONFIRMED}, …)
     * @param confidence   уверенность ({@code HIGH}/{@code LOW}/{@code NONE})
     * @param reason       обоснование
     * @param postingCount публикаций на первой странице ленты
     */
    public record Verdict(String state, String confidence, String reason, int postingCount) {
    }

    /**
     * Что заведено при подтверждении ({@code null} — ещё не подтверждён).
     *
     * @param companyId компания
     * @param sourceId  источник сбора
     */
    public record Link(Long companyId, Long sourceId) {
    }

    /**
     * Представление кандидата в очереди (поля сгруппированы, §78).
     *
     * @param id        идентификатор кандидата
     * @param board     доска
     * @param verdict   итог проверки
     * @param link      компания и источник после подтверждения
     * @param createdAt когда обнаружен
     */
    public record CandidateResponse(Long id, Board board, Verdict verdict, Link link, Instant createdAt) {

        static CandidateResponse of(EmployerCandidate candidate) {
            return new CandidateResponse(
                    candidate.getId(),
                    new Board(candidate.getProviderCode(), candidate.getSlug(), candidate.getBaseUrl()),
                    new Verdict(candidate.getState().name(), candidate.getConfidence().name(),
                            candidate.getReason(), candidate.getPostingCount()),
                    new Link(candidate.getCompanyId(), candidate.getSourceId()),
                    candidate.getCreatedAt());
        }
    }
}
