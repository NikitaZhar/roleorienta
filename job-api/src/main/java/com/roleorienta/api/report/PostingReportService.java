package com.roleorienta.api.report;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.posting.PostingReadRepository;
import com.roleorienta.api.report.PostingReportDtos.AdminReportResponse;
import com.roleorienta.api.report.PostingReportDtos.ReportResponse;
import com.roleorienta.core.domain.JobPosting;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика жалоб на публикации (§46) — «сообщить об ошибке», всегда в пределах автора (A23).
 *
 * <p><b>Приватность (A23, §3.9).</b> Автор берётся только из сессии; жалобы читаются по id
 * автора. Список отдаёт только свои жалобы.</p>
 *
 * <p><b>Идемпотентность.</b> Повторная жалоба того же автора на ту же публикацию не создаёт
 * дубля (уникальный ключ {@code (app_user_id, job_posting_id)}) — возвращается существующая,
 * причина и комментарий не перезаписываются (правку оставляем следующему срезу).</p>
 */
@Service
public class PostingReportService {

    private final PostingReportRepository reports;
    private final AppUserRepository users;
    private final PostingReadRepository postings;

    public PostingReportService(PostingReportRepository reports,
                                AppUserRepository users,
                                PostingReadRepository postings) {
        this.reports = reports;
        this.users = users;
        this.postings = postings;
    }

    /**
     * Пожаловаться на публикацию (идемпотентно): повторная жалоба того же автора возвращает
     * существующую.
     *
     * @throws ResponseStatusException {@code 404}, если публикации нет
     */
    @Transactional
    public ReportResponse create(Authentication authentication, Long postingId,
                                 PostingReportReason reason, String comment) {
        AppUser owner = currentUser(authentication);
        JobPosting posting = postings.findById(postingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Публикация не найдена: " + postingId));

        PostingReport report = reports
                .findByReporter_IdAndPosting_Id(owner.getId(), postingId)
                .orElse(null);
        if (report == null) {
            report = new PostingReport();
            report.setReporter(owner);
            report.setPosting(posting);
            report.setReason(reason);
            report.setComment(comment);
            report.setStatus(PostingReportStatus.OPEN);
            report = reports.save(report);
        }
        return ReportResponse.of(report);
    }

    /** Жалобы текущего пользователя (новые сверху). */
    @Transactional(readOnly = true)
    public List<ReportResponse> list(Authentication authentication) {
        AppUser owner = currentUser(authentication);
        return reports.findByReporter_IdOrderByIdDesc(owner.getId())
                .stream()
                .map(ReportResponse::of)
                .toList();
    }

    /**
     * Очередь разбора жалоб (§47) — все жалобы, новые сверху; при заданном статусе — только его.
     * Доступ admin-only через {@code /api/v1/admin/**} (роль проверяет SecurityConfig).
     */
    @Transactional(readOnly = true)
    public List<AdminReportResponse> listForModeration(PostingReportStatus status) {
        List<PostingReport> found = (status == null)
                ? reports.findAllByOrderByIdDesc()
                : reports.findByStatusOrderByIdDesc(status);
        return found.stream().map(AdminReportResponse::of).toList();
    }

    /**
     * Разобрать жалобу (§47): перевести из {@code OPEN} в {@code RESOLVED} или {@code DISMISSED}.
     * Повтор того же терминального статуса — идемпотентный успех; иной терминальный → 409;
     * возврат в {@code OPEN} не поддерживается → 400.
     *
     * @throws ResponseStatusException 404 (нет жалобы), 400 (target OPEN), 409 (уже разобрана иначе)
     */
    @Transactional
    public AdminReportResponse triage(Long reportId, PostingReportStatus target) {
        if (target == PostingReportStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Возврат жалобы в OPEN не поддерживается");
        }
        PostingReport report = reports.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Жалоба не найдена: " + reportId));

        PostingReportStatus current = report.getStatus();
        if (current != target) {
            if (current != PostingReportStatus.OPEN) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Жалоба уже разобрана: " + current);
            }
            report.setStatus(target);
            report = reports.save(report);
        }
        return AdminReportResponse.of(report);
    }

    private AppUser currentUser(Authentication authentication) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход");
        }
        return users.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Сессия недействительна"));
    }
}
