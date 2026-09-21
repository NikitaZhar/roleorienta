package com.roleorienta.worker.collect;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.PendingChange;
import com.roleorienta.core.domain.PostingRevision;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Правило «что считать изменением» (§6) и совместная запись ревизии и журнала
 * {@link PendingChange} (§8, A16). Репозитории — заглушки (без БД).
 */
class PostingRevisionRecorderTest {

    private final PostingRevisionRepository revisions = mock(PostingRevisionRepository.class);
    private final PendingChangeRepository pending = mock(PendingChangeRepository.class);
    private final PostingRevisionRecorder recorder = new PostingRevisionRecorder(revisions, pending);
    private final JobPosting posting = mock(JobPosting.class);

    @Test
    void realChangeRecordsRevisionAndPendingChange() {
        recorder.recordIfChanged(posting, "salary_min", "1000", "2000", Instant.now());

        verify(revisions).save(any(PostingRevision.class));
        verify(pending).save(any(PendingChange.class));
    }

    @Test
    void firstFillRecordsNothing() {
        recorder.recordIfChanged(posting, "salary_min", null, "2000", Instant.now());

        verifyNoInteractions(revisions, pending);
    }

    @Test
    void sameValueRecordsNothing() {
        recorder.recordIfChanged(posting, "salary_min", "2000", "2000", Instant.now());

        verify(revisions, never()).save(any());
        verify(pending, never()).save(any());
    }
}
