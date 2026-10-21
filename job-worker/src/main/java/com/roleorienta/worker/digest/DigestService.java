package com.roleorienta.worker.digest;

import com.roleorienta.worker.delivery.DeliveryProperties;
import com.roleorienta.worker.digest.DigestRepository.Fresh;
import com.roleorienta.worker.digest.DigestRepository.Recipient;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сводка новых вакансий одному пользователю за день (бизнес-описание §6; стенограмма §164, аудит §165).
 *
 * <p>Одна транзакция: строка пользователя блокируется; сводка за этот день уже отработана или частота сейчас «не
 * присылать» — ничего; новые вакансии отбираются и отмечаются попавшими в письмо одним запросом
 * ({@link DigestRepository#takeFresh}); новых нет — письма нет, день отмечается; есть — письмо уходит, затем
 * отмечается день. Письмо отправляется до фиксации отметок: упадёт фиксация — письмо может прийти повторно, но не
 * потеряется (выбор §164). Почтовый сервер не принял письмо — исключение, транзакция откатывается вместе с отметками
 * вакансий, задание повторяется. Отправка идёт под блокировкой строки пользователя: пока сервер отвечает (до 10 с на
 * шаг), ждут только изменения этого же пользователя — смена статуса, предложение позиции, «Settings».</p>
 */
@Service
public class DigestService {

    private static final Logger LOG = LoggerFactory.getLogger(DigestService.class);

    /** Частота «не присылать» ({@code user_account.digest_frequency}). */
    private static final String NEVER = "NEVER";

    private final DigestRepository repository;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final DigestProperties properties;
    private final DeliveryProperties delivery;
    private final String mailHost;

    /**
     * @param repository данные сводки
     * @param mailSender отправка писем (Spring Boot создаёт её по {@code spring.mail.*})
     * @param properties отправитель, адрес приложения, строк в письме
     * @param delivery   срок без подтверждения — видимость в результатах, как у выдачи
     * @param mailHost   почтовый сервер ({@code spring.mail.host}); пусто — почта не настроена: Spring Boot создаёт
     *                   отправку писем и при пустом значении, поэтому проверка — здесь (аудит §165)
     */
    public DigestService(DigestRepository repository, ObjectProvider<JavaMailSender> mailSender,
            DigestProperties properties, DeliveryProperties delivery, @Value("${spring.mail.host:}") String mailHost) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.properties = properties;
        this.delivery = delivery;
        this.mailHost = mailHost;
    }

    /**
     * @param userId пользователь
     * @param day    день по его часам
     * @return {@code true} — письмо отправлено
     * @throws IllegalStateException почта не настроена (нет сервера или отправителя)
     * @throws MailException         почтовый сервер не принял письмо
     */
    @Transactional
    public boolean send(long userId, LocalDate day) {
        Optional<Recipient> found = repository.lockRecipient(userId);
        if (found.isEmpty() || NEVER.equals(found.get().frequency())
                || found.get().day() != null && !found.get().day().isBefore(day)) {
            return false;
        }
        Recipient recipient = found.get();
        Instant now = Instant.now();
        Fresh fresh = repository.takeFresh(userId, now.minus(delivery.hideAfter()), now, properties.maxRows());
        if (fresh.count() == 0) {
            repository.markDone(userId, day);
            return false;
        }
        deliver(recipient, DigestMail.of(recipient.language(), fresh, properties.appUrl()));
        repository.markDone(userId, day);
        LOG.info("Digest for user {} on {}: {} new vacancies", userId, day, fresh.count());
        return true;
    }

    private void deliver(Recipient recipient, DigestMail mail) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailHost.isBlank() || properties.from().isBlank()) {
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
