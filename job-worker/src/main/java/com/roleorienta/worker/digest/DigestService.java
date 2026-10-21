package com.roleorienta.worker.digest;

import com.roleorienta.worker.digest.DigestRepository.Fresh;
import com.roleorienta.worker.digest.DigestRepository.Recipient;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сводка новых вакансий одному пользователю за день (бизнес-описание §6; стенограмма §164).
 *
 * <p>Одна транзакция: строка пользователя блокируется; сводка за этот день уже отработана — ничего; новых вакансий
 * нет — письма нет, день отмечается; есть — письмо уходит, затем отмечаются день и граница (момент выдачи последней
 * вакансии письма). Письмо отправляется до записи отметки: упадёт запись — письмо может прийти повторно, но не
 * потеряется (выбор §164). Почтовый сервер не ответил — исключение, транзакция откатывается, задание повторяется.</p>
 */
@Service
public class DigestService {

    private static final Logger LOG = LoggerFactory.getLogger(DigestService.class);

    private final DigestRepository repository;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final DigestProperties properties;

    /**
     * @param repository данные сводки
     * @param mailSender отправка писем (Spring Boot создаёт её по {@code spring.mail.*})
     * @param properties отправитель, адрес приложения, строк в письме
     */
    public DigestService(DigestRepository repository, ObjectProvider<JavaMailSender> mailSender,
            DigestProperties properties) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * @param userId пользователь
     * @param day    день по его часам
     * @return {@code true} — письмо отправлено
     * @throws IllegalStateException почта не настроена (нет отправителя или сервера)
     * @throws MailException         почтовый сервер не принял письмо
     */
    @Transactional
    public boolean send(long userId, LocalDate day) {
        Optional<Recipient> found = repository.lockRecipient(userId);
        if (found.isEmpty() || found.get().day() != null && !found.get().day().isBefore(day)) {
            return false;
        }
        Recipient recipient = found.get();
        Fresh fresh = repository.fresh(userId, recipient.sentUntil(), properties.maxRows());
        if (fresh.count() == 0) {
            repository.markDone(userId, day, null);
            return false;
        }
        deliver(recipient, DigestMail.of(recipient.language(), fresh, properties.appUrl()));
        repository.markDone(userId, day, fresh.until());
        LOG.info("Digest for user {} on {}: {} new vacancies", userId, day, fresh.count());
        return true;
    }

    private void deliver(Recipient recipient, DigestMail mail) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || properties.from().isBlank()) {
            throw new IllegalStateException("Mail is not configured: set MAIL_HOST and MAIL_FROM");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(recipient.email());
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), mail.html());
            sender.send(message);
        } catch (MessagingException failure) {
            throw new IllegalStateException("Digest message is not built", failure);
        }
    }
}
