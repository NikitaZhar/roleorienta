package com.roleorienta.api.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Тела запросов и ответов эндпоинтов собеседований (§43). */
public final class InterviewDtos {

    private InterviewDtos() {
    }

    /** Назначить собеседование: момент в UTC ({@code scheduledAt}) и таймзона (IANA). */
    public record ScheduleInterviewRequest(@NotNull Instant scheduledAt, @NotBlank String zoneId) {
    }

    /** Перенести собеседование: новый момент и таймзона. */
    public record RescheduleInterviewRequest(@NotNull Instant scheduledAt, @NotBlank String zoneId) {
    }

    /** Собеседование в ответе. */
    public record InterviewResponse(Long id, Instant scheduledAt, String zoneId,
                                    String status, Instant createdAt) {

        static InterviewResponse of(Interview interview) {
            return new InterviewResponse(
                    interview.getId(),
                    interview.getScheduledAt(),
                    interview.getZoneId(),
                    interview.getStatus().name(),
                    interview.getCreatedAt());
        }
    }
}
