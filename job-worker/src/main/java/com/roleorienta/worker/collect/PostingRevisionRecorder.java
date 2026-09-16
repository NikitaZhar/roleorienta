package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.PostingRevision;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Фиксирует изменения полей публикации между сборами (§6, история изменений).
 *
 * <p>Пишет строку «было → стало» только для **реального** изменения: если прежнее
 * значение было не пустым и отличается от нового. Первичное заполнение
 * ({@code oldValue == null}) изменением не считается — это первое наблюдение поля,
 * а не смена. Логика в одном месте, чтобы правило «что считать изменением» не
 * расползалось по обработчикам и покрывалось тестами отдельно.</p>
 */
@Component
public class PostingRevisionRecorder {

    private final PostingRevisionRepository revisionRepository;

    /**
     * @param revisionRepository хранилище истории изменений
     */
    public PostingRevisionRecorder(PostingRevisionRepository revisionRepository) {
        this.revisionRepository = revisionRepository;
    }

    /**
     * Записывает изменение поля, если оно реальное (было не пусто и отличается).
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
    }
}
