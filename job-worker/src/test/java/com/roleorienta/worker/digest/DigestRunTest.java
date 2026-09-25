package com.roleorienta.worker.digest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.worker.digest.DigestContent.NewPosting;
import com.roleorienta.worker.digest.DigestLookup.Recipient;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Проход дайджеста (A7, §88): письмо только при содержимом, окно от прошлого, повтор при сбое SMTP. */
class DigestRunTest {

    private static final Instant NOW = Instant.parse("2026-09-25T04:50:00Z");
    private static final Instant SUBSCRIBED = Instant.parse("2026-09-24T04:50:00Z");
    private static final DigestContent ONE_NEW = new DigestContent(
            List.of(new NewPosting("Java Developer", "https://jobs.example/1", "Accenture", CoverageState.SITE_ONLY)),
            List.of());
    private static final DigestContent NOTHING = new DigestContent(List.of(), List.of());

    private final DigestLookup lookup = mock(DigestLookup.class);
    private final DigestDeliveryRepository deliveries = mock(DigestDeliveryRepository.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final DigestRun digest = new DigestRun(lookup, deliveries, mailSender,
            new DigestProperties(true, "digest@example.test", ZoneId.of("Europe/Bratislava"), 3, 30),
            mock(PostgresLeaderLock.class));

    private DigestDelivery planned;

    @BeforeEach
    void pendingIsWhatWasPlanned() {
        when(deliveries.save(any(DigestDelivery.class))).thenAnswer(invocation -> {
            DigestDelivery delivery = invocation.getArgument(0);
            if (planned == null) {
                planned = delivery;
            }
            return delivery;
        });
        when(deliveries.findByStateOrderById(any(), any(Limit.class)))
                .thenAnswer(invocation -> planned == null ? List.of() : List.of(planned));
        when(lookup.emailOf(7L)).thenReturn(Optional.of("user@example.test"));
    }

    @Test
    void recipientWithContentGetsOneMailForWindowSinceSubscription() {
        when(lookup.recipients()).thenReturn(List.of(new Recipient(7L, "user@example.test", SUBSCRIBED)));
        when(lookup.content(7L, SUBSCRIBED, NOW)).thenReturn(ONE_NEW);

        digest.runOnce(NOW);

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertEquals("user@example.test", mail.getValue().getTo()[0]);
        assertEquals("digest@example.test", mail.getValue().getFrom());
        assertEquals(DigestDeliveryState.SENT, planned.getState());
        assertEquals(SUBSCRIBED, planned.getWindowStart());
        assertEquals(NOW, planned.getWindowEnd());
    }

    @Test
    void emptyWindowSendsNothing() {
        when(lookup.recipients()).thenReturn(List.of(new Recipient(7L, "user@example.test", SUBSCRIBED)));
        when(lookup.content(7L, SUBSCRIBED, NOW)).thenReturn(NOTHING);

        digest.runOnce(NOW);

        verify(deliveries, never()).save(any());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void smtpFailureKeepsMailPendingUntilAttemptsRunOut() {
        when(lookup.recipients()).thenReturn(List.of(new Recipient(7L, "user@example.test", SUBSCRIBED)));
        when(lookup.content(7L, SUBSCRIBED, NOW)).thenReturn(ONE_NEW);
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        digest.runOnce(NOW);
        assertEquals(DigestDeliveryState.PENDING, planned.getState());
        assertEquals(1, planned.getAttempts());

        when(lookup.recipients()).thenReturn(List.of());
        digest.runOnce(NOW.plusSeconds(60));
        digest.runOnce(NOW.plusSeconds(120));
        assertEquals(DigestDeliveryState.FAILED, planned.getState());
        assertEquals(3, planned.getAttempts());
        assertEquals("connection refused", planned.getLastError());
    }
}
