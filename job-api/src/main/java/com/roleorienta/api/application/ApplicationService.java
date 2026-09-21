package com.roleorienta.api.application;

import com.roleorienta.api.application.ApplicationDtos.ApplicationCardResponse;
import com.roleorienta.api.application.ApplicationDtos.ApplicationResponse;
import com.roleorienta.api.application.ApplicationDtos.NoteResponse;
import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.posting.PostingReadRepository;
import com.roleorienta.core.domain.JobPosting;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика откликов и заметок (§7) — всегда в пределах текущего пользователя.
 *
 * <p><b>Проверка владельца (A23, §3.9).</b> Владелец берётся только из сессии; отклики и
 * заметки читаются/меняются по id владельца, доступ к чужому id → {@code 404}.</p>
 *
 * <p><b>Оптимистичная конкуренция (A19, §42).</b> Статус меняется только с корректным
 * предусловием {@code If-Match} (сильный ETag = версия отклика): нет заголовка → {@code 428},
 * устаревший → {@code 412}. Недопустимый переход статуса → {@code 409}. Гонка одновременных
 * изменений ловится {@code @Version} на сохранении → {@code 409}. Повтор той же смены (статус
 * уже такой) при совпавшем {@code If-Match} — идемпотентный успех (RFC 9110).</p>
 */
@Service
public class ApplicationService {

    /** Допустимые переходы статуса (§7.8). Терминальные состояния переходов не имеют. */
    private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED = Map.of(
            ApplicationStatus.APPLIED,
            EnumSet.of(ApplicationStatus.INTERVIEWING, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN),
            ApplicationStatus.INTERVIEWING,
            EnumSet.of(ApplicationStatus.OFFER, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN),
            ApplicationStatus.OFFER,
            EnumSet.of(ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN),
            ApplicationStatus.REJECTED, EnumSet.noneOf(ApplicationStatus.class),
            ApplicationStatus.WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class));

    private final ApplicationRepository applications;
    private final ApplicationNoteRepository notes;
    private final AppUserRepository users;
    private final PostingReadRepository postings;

    public ApplicationService(ApplicationRepository applications,
                              ApplicationNoteRepository notes,
                              AppUserRepository users,
                              PostingReadRepository postings) {
        this.applications = applications;
        this.notes = notes;
        this.users = users;
        this.postings = postings;
    }

    /**
     * Создать отклик на публикацию (идемпотентно): повторный отклик на ту же публикацию
     * возвращает существующий, статус не сбрасывается.
     *
     * @throws ResponseStatusException {@code 404}, если публикации нет
     */
    @Transactional
    public ApplicationResponse create(Authentication authentication, Long postingId) {
        AppUser owner = currentUser(authentication);
        JobPosting posting = postings.findById(postingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Публикация не найдена: " + postingId));

        Application application = applications
                .findByUser_IdAndPosting_Id(owner.getId(), postingId)
                .orElse(null);
        if (application == null) {
            application = new Application();
            application.setUser(owner);
            application.setPosting(posting);
            application.setStatus(ApplicationStatus.APPLIED);
            application = applications.save(application);
        }
        return ApplicationResponse.of(application);
    }

    /** Отклики текущего пользователя (новые сверху). */
    @Transactional(readOnly = true)
    public List<ApplicationResponse> list(Authentication authentication) {
        AppUser owner = currentUser(authentication);
        return applications.findByUser_IdOrderByIdDesc(owner.getId())
                .stream()
                .map(ApplicationResponse::of)
                .toList();
    }

    /**
     * Карточка отклика с заметками.
     *
     * @throws ResponseStatusException {@code 404}, если отклика нет или он чужой
     */
    @Transactional(readOnly = true)
    public ApplicationCardResponse get(Authentication authentication, Long applicationId) {
        AppUser owner = currentUser(authentication);
        Application application = requireOwned(applicationId, owner);
        return card(application);
    }

    /**
     * Сменить статус отклика с предусловием {@code If-Match} (A19).
     *
     * @param authentication текущая аутентификация (владелец)
     * @param applicationId  id отклика
     * @param target         целевой статус
     * @param ifMatch        заголовок {@code If-Match} (сильный ETag = версия)
     * @return обновлённая карточка отклика
     * @throws ResponseStatusException 404 (нет/чужой), 428 (нет If-Match), 412 (устаревший),
     *                                 409 (недопустимый переход или гонка версий)
     */
    @Transactional
    public ApplicationCardResponse updateStatus(Authentication authentication, Long applicationId,
                                                ApplicationStatus target, String ifMatch) {
        AppUser owner = currentUser(authentication);
        Application application = requireOwned(applicationId, owner);
        requireIfMatch(ifMatch, application.getVersion());

        ApplicationStatus current = application.getStatus();
        if (current != target) {
            if (!ALLOWED.getOrDefault(current, EnumSet.noneOf(ApplicationStatus.class)).contains(target)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Недопустимый переход статуса: " + current + " → " + target);
            }
            application.setStatus(target);
            try {
                applications.saveAndFlush(application);
            } catch (OptimisticLockingFailureException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Отклик изменён другим запросом, обновите и повторите");
            }
        }
        return card(application);
    }

    /**
     * Добавить заметку к отклику.
     *
     * @throws ResponseStatusException {@code 404}, если отклика нет или он чужой
     */
    @Transactional
    public NoteResponse addNote(Authentication authentication, Long applicationId, String body) {
        AppUser owner = currentUser(authentication);
        Application application = requireOwned(applicationId, owner);

        ApplicationNote note = new ApplicationNote();
        note.setApplication(application);
        note.setBody(body);
        return NoteResponse.of(notes.save(note));
    }

    /**
     * Изменить текст заметки отклика (§44).
     *
     * @throws ResponseStatusException {@code 404}, если заметки нет или она чужая
     */
    @Transactional
    public NoteResponse updateNote(Authentication authentication, Long applicationId,
                                   Long noteId, String body) {
        AppUser owner = currentUser(authentication);
        ApplicationNote note = requireOwnedNote(noteId, applicationId, owner);
        note.setBody(body);
        return NoteResponse.of(notes.save(note));
    }

    /**
     * Удалить заметку отклика (§44).
     *
     * @throws ResponseStatusException {@code 404}, если заметки нет или она чужая
     */
    @Transactional
    public void deleteNote(Authentication authentication, Long applicationId, Long noteId) {
        AppUser owner = currentUser(authentication);
        ApplicationNote note = requireOwnedNote(noteId, applicationId, owner);
        notes.delete(note);
    }

    private ApplicationNote requireOwnedNote(Long noteId, Long applicationId, AppUser owner) {
        return notes.findByIdAndApplication_IdAndApplication_User_Id(noteId, applicationId, owner.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Заметка не найдена: " + noteId));
    }

    /**
     * Поднять статус отклика до {@link ApplicationStatus#INTERVIEWING} при назначении
     * собеседования (§45) — только из {@link ApplicationStatus#APPLIED} (допустимый переход
     * §7.8). Из прочих статусов (в т.ч. терминальных) — без изменений. Вызывается сервером,
     * поэтому без предусловия {@code If-Match}; {@code @Version} растёт как при любом изменении.
     */
    @Transactional
    public void markInterviewing(Application application) {
        if (application.getStatus() == ApplicationStatus.APPLIED) {
            application.setStatus(ApplicationStatus.INTERVIEWING);
            applications.save(application);
        }
    }

    private ApplicationCardResponse card(Application application) {
        return ApplicationCardResponse.of(application,
                notes.findByApplication_IdOrderByIdDesc(application.getId()));
    }

    private void requireIfMatch(String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "Требуется заголовок If-Match с текущей версией отклика");
        }
        if (ifMatch.trim().equals("*")) {
            return;
        }
        Long expected = parseETag(ifMatch);
        if (expected == null || expected != currentVersion) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Устаревшая версия отклика (If-Match не совпал)");
        }
    }

    private Long parseETag(String raw) {
        String value = raw.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Application requireOwned(Long applicationId, AppUser owner) {
        return applications.findByIdAndUser_Id(applicationId, owner.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Отклик не найден: " + applicationId));
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
