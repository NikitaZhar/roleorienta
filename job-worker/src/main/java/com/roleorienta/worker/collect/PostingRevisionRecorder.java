package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.PendingChange;
import com.roleorienta.core.domain.PostingRevision;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Фиксирует изменения полей публикации между сборами (§6, история изменений).
 *
 * <p>Пишет строку «было → стало» только для <b>реального</b> изменения: если прежнее
 * значение было не пустым и отличается от нового. Первичное заполнение
 * ({@code oldValue == null}) изменением не считается — это первое наблюдение поля,
 * а не смена. Логика в одном месте, чтобы правило «что считать изменением» не
 * расползалось по обработчикам и покрывалось тестами отдельно.</p>
 *
 * <p>Вместе с ревизией в той же транзакции пишется запись в устойчивый журнал
 * {@link PendingChange} (§8, A16): она ждёт сопоставления с подписками
 * ({@code MATCH_SUBSCRIPTIONS}, §39) и не зависит от брокера — уведомление не теряется
 * при сбое между записью ревизии и рассылкой.</p>
 */
@Component
public class PostingRevisionRecorder {

    private final PostingRevisionRepository revisionRepository;
    private final PendingChangeRepository pendingChangeRepository;

    /**
     * @param revisionRepository      хранилище истории изменений
     * @param pendingChangeRepository устойчивый журнал изменений для сопоставления с подписками
     */
    public PostingRevisionRecorder(PostingRevisionRepository revisionRepository,
                                   PendingChangeRepository pendingChangeRepository) {
        this.revisionRepository = revisionRepository;
        this.pendingChangeRepository = pendingChangeRepository;
    }

    /**
     * Записывает изменение поля, если оно реальное (было не пусто и отличается): строку
     * истории {@link PostingRevision} и запись журнала {@link PendingChange} — вместе.
     *
     * @param posting    публикация
     * @param fieldName  имя поля
     * @param oldValue   прежнее значение (строкой)
     * @param newValue   новое значение (строкой)
     * @param detectedAt момент обнаружения
     */
    public void recordIfChanged(JobPosting posting, String fieldName, String oldValue,
                                String newValue, Instant detectedAt) {
        if (oldValue == null || Objects.equals(oldValue, newValue)) {
            return;
        }
        revisionRepository.save(new PostingRevision(posting, fieldName, oldValue, newValue, detectedAt));
        pendingChangeRepository.save(new PendingChange(posting, fieldName, detectedAt));
    }
}
