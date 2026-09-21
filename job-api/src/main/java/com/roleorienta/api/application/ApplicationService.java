package com.roleorienta.api.application;

import com.roleorienta.api.application.ApplicationDtos.ApplicationCardResponse;
import com.roleorienta.api.application.ApplicationDtos.ApplicationResponse;
import com.roleorienta.api.application.ApplicationDtos.NoteResponse;
import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.posting.PostingReadRepository;
import com.roleorienta.core.domain.JobPosting;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика откликов и заметок (§7) — всегда в пределах текущего пользователя.
 *
 * <p><b>Проверка владельца (A23, §3.9).</b> Владелец берётся только из аутентификации
 * (сессии): email → {@link AppUser}. Отклики и заметки читаются/меняются по id владельца,
 * поэтому чужой отклик не виден и недоступен (доступ к чужому id → {@code 404}, чтобы не
 * раскрывать существование). Существование публикации проверяется переиспользуемым
 * {@link PostingReadRepository} (иначе {@code 404}).</p>
 */
@Service
public class ApplicationService {

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
     * @param authentication текущая аутентификация (владелец)
     * @param postingId      id публикации
     * @return отклик
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

    /**
     * Отклики текущего пользователя (новые сверху).
     *
     * @param authentication текущая аутентификация (владелец)
     * @return отклики владельца
     */
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
     * @param authentication текущая аутентификация (владелец)
     * @param applicationId  id отклика
     * @return карточка отклика
     * @throws ResponseStatusException {@code 404}, если отклика нет или он не принадлежит владельцу
     */
    @Transactional(readOnly = true)
    public ApplicationCardResponse get(Authentication authentication, Long applicationId) {
        AppUser owner = currentUser(authentication);
        Application application = requireOwned(applicationId, owner);
        return ApplicationCardResponse.of(application,
                notes.findByApplication_IdOrderByIdDesc(applicationId));
    }

    /**
     * Добавить заметку к отклику.
     *
     * @param authentication текущая аутентификация (владелец)
     * @param applicationId  id отклика
     * @param body           текст заметки
     * @return созданная заметка
     * @throws ResponseStatusException {@code 404}, если отклика нет или он не принадлежит владельцу
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
