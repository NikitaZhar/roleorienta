package com.roleorienta.worker.digest;

import com.roleorienta.worker.digest.DigestComposer.Message;
import com.roleorienta.worker.digest.DigestLookup.Recipient;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Проход ежедневного дайджеста (A7, §88) под leader-lock: (1) каждому подписчику с новыми
 * вакансиями или изменениями в окне «от конца прошлого окна до сейчас» заводится письмо
 * ({@link DigestDelivery}, {@code PENDING}); нет содержимого — письма нет, окно не сдвигается;
 * (2) письма {@code PENDING} (новые и ждущие повтора) отправляются через SMTP.
 *
 * <p>Доставка — не «ровно один раз» (technical-design A16): если процесс упадёт после приёма
 * письма сервером, но до фиксации транзакции, письмо уйдёт повторно. Ошибка одного письма не
 * мешает остальным: попытка считается, после {@code maxAttempts} — {@code FAILED}.</p>
 */
@Component
public class DigestRun {

    /** Ключ advisory-лока (реестр ключей — {@link PostgresLeaderLock}). */
    static final long DIGEST_LOCK_KEY = 1007L;

    private static final Logger log = LoggerFactory.getLogger(DigestRun.class);

    /** Писем за проход (новых и повторов); остальные — в следующем проходе. */
    static final int BATCH = 200;

    private final DigestLookup lookup;
    private final DigestDeliveryRepository deliveries;
    private final JavaMailSender mailSender;
    private final DigestProperties properties;
    private final PostgresLeaderLock leaderLock;

    /**
     * @param lookup     получатели и содержимое окна
     * @param deliveries доставки
     * @param mailSender SMTP-клиент Spring ({@code spring.mail.*})
     * @param properties настройки дайджеста
     * @param leaderLock leader-lock прохода
     */
    public DigestRun(DigestLookup lookup, DigestDeliveryRepository deliveries, JavaMailSender mailSender,
                     DigestProperties properties, PostgresLeaderLock leaderLock) {
        this.lookup = lookup;
        this.deliveries = deliveries;
        this.mailSender = mailSender;
        this.properties = properties;
        this.leaderLock = leaderLock;
    }

    /**
     * Один проход под leader-lock.
     *
     * @return {@code true}, если эта реплика была лидером
     */
    public boolean run() {
        return leaderLock.runIfLeader(DIGEST_LOCK_KEY, () -> runOnce(Instant.now()));
    }

    /**
     * Заводит письма за окно, кончающееся в {@code now}, и отправляет ждущие.
     *
     * @param now конец окна и момент отправки
     */
    void runOnce(Instant now) {
        int planned = plan(now);
        List<DigestDelivery> pending = deliveries.findByStateOrderById(DigestDeliveryState.PENDING, Limit.of(BATCH));
        int sent = 0;
        for (DigestDelivery delivery : pending) {
            sent += send(delivery, now) ? 1 : 0;
        }
        log.info("Дайджест: заведено писем {}, к отправке {}, отправлено {}", planned, pending.size(), sent);
    }

    private int plan(Instant now) {
        int planned = 0;
        for (Recipient recipient : lookup.recipients()) {
            if (!recipient.windowStart().isBefore(now)
                    || lookup.content(recipient.userId(), recipient.windowStart(), now).isEmpty()) {
                continue;
            }
            deliveries.save(new DigestDelivery(recipient.userId(), recipient.windowStart(), now));
            planned++;
        }
        return planned;
    }

    /** Одна попытка отправки; письмо собирается заново из данных окна. */
    private boolean send(DigestDelivery delivery, Instant now) {
        int maxAttempts = properties.maxAttempts();
        Optional<String> email = lookup.emailOf(delivery.getAppUserId());
        DigestContent content = lookup.content(delivery.getAppUserId(), delivery.getWindowStart(),
                delivery.getWindowEnd());
        if (email.isEmpty() || content.isEmpty()) {
            delivery.markFailed(email.isEmpty() ? "пользователя нет" : "окно пусто");
            deliveries.save(delivery);
            return false;
        }
        Message message = DigestComposer.compose(content,
                delivery.getWindowEnd().atZone(properties.zone()).toLocalDate(), properties.maxItems());
        try {
            mailSender.send(mail(email.get(), message));
            delivery.markSent(now);
            return true;
        } catch (MailException exception) {
            log.warn("Дайджест: письмо {} пользователю {} не отправлено (попытка {} из {}): {}",
                    delivery.getId(), delivery.getAppUserId(), delivery.getAttempts() + 1, maxAttempts,
                    exception.getMessage());
            delivery.markFailedAttempt(exception.getMessage(), maxAttempts);
            return false;
        } finally {
            deliveries.save(delivery);
        }
    }

    private SimpleMailMessage mail(String to, Message message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.from());
        mail.setTo(to);
        mail.setSubject(message.subject());
        mail.setText(message.text());
        return mail;
    }
}
