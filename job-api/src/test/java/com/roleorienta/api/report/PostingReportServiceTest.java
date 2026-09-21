package com.roleorienta.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.posting.PostingReadRepository;
import com.roleorienta.api.report.PostingReportDtos.ReportResponse;
import com.roleorienta.core.domain.JobPosting;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика жалоб на публикации (§46): резолв автора (A23), идемпотентность, 404 на нет-публикацию,
 * список только своих. Репозитории и аутентификация — заглушки.
 */
class PostingReportServiceTest {

    private final PostingReportRepository reports = mock(PostingReportRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PostingReadRepository postings = mock(PostingReadRepository.class);
    private final PostingReportService service = new PostingReportService(reports, users, postings);

    private final Authentication auth = mock(Authentication.class);
    private final AppUser owner = mock(AppUser.class);
    private final JobPosting posting = mock(JobPosting.class);

    private void withOwner() {
        when(auth.getName()).thenReturn("u@example.com");
        when(owner.getId()).thenReturn(1L);
        when(users.findByEmailIgnoreCase("u@example.com")).thenReturn(Optional.of(owner));
    }

    private static int statusOf(ResponseStatusException e) {
        return e.getStatusCode().value();
    }

    @Test
    void createNewReport() {
        withOwner();
        when(posting.getId()).thenReturn(10L);
        when(postings.findById(10L)).thenReturn(Optional.of(posting));
        when(reports.findByReporter_IdAndPosting_Id(1L, 10L)).thenReturn(Optional.empty());
        when(reports.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportResponse response = service.create(auth, 10L, PostingReportReason.BROKEN_LINK, "404 на карьерной");

        ArgumentCaptor<PostingReport> captor = ArgumentCaptor.forClass(PostingReport.class);
        verify(reports).save(captor.capture());
        assertEquals(owner, captor.getValue().getReporter());
        assertEquals(PostingReportReason.BROKEN_LINK, captor.getValue().getReason());
        assertEquals(PostingReportStatus.OPEN, captor.getValue().getStatus());
        assertEquals("BROKEN_LINK", response.reason());
        assertEquals("OPEN", response.status());
        assertEquals(10L, response.postingId());
    }

    @Test
    void createIsIdempotentForExisting() {
        withOwner();
        when(postings.findById(10L)).thenReturn(Optional.of(posting));
        when(posting.getId()).thenReturn(10L);
        PostingReport existing = new PostingReport();
        existing.setPosting(posting);
        existing.setReason(PostingReportReason.DUPLICATE);
        existing.setStatus(PostingReportStatus.OPEN);
        when(reports.findByReporter_IdAndPosting_Id(1L, 10L)).thenReturn(Optional.of(existing));

        ReportResponse response = service.create(auth, 10L, PostingReportReason.OUTDATED, "изменил мнение");

        verify(reports, never()).save(any());
        assertEquals("DUPLICATE", response.reason());
    }

    @Test
    void createMissingPostingIsNotFound() {
        withOwner();
        when(postings.findById(999L)).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.create(auth, 999L, PostingReportReason.OTHER, null));
        assertEquals(404, statusOf(e));
        verify(reports, never()).save(any());
    }

    @Test
    void listReturnsOwnReports() {
        withOwner();
        PostingReport report = new PostingReport();
        report.setPosting(posting);
        report.setReason(PostingReportReason.OTHER);
        report.setStatus(PostingReportStatus.OPEN);
        when(posting.getId()).thenReturn(10L);
        when(reports.findByReporter_IdOrderByIdDesc(1L)).thenReturn(List.of(report));

        List<ReportResponse> response = service.list(auth);

        assertEquals(1, response.size());
        assertEquals("OTHER", response.get(0).reason());
    }
}
