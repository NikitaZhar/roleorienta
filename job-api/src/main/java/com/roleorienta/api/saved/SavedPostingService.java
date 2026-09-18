package com.roleorienta.api.saved;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.posting.PostingReadRepository;
import com.roleorienta.api.saved.SavedPostingDtos.SavedPostingResponse;
import com.roleorienta.core.domain.JobPosting;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика персональных маркеров публикаций (§7): сохранить, скрыть, снять и перечислить
 * сохранённые — всегда в пределах текущего пользователя.
 *
 * <p><b>Проверка владельца (A23, §3.9).</b> Владелец берётся только из аутентификации
 * (сессии): {@code Authentication.getName()} даёт email, по нему находится {@link AppUser}.
 * Идентификатор пользователя никогда не принимается из запроса, поэтому один пользователь
 * не может прочитать или изменить маркер другого — все операции идут по id владельца.</p>
 *
 * <p>Слои соблюдены (§3.3): сервис содержит логику, репозитории — только доступ. Три
 * зависимости — в пределах лимита §3.10. Существующий {@link PostingReadRepository}
 * переиспользуется без изменений: его {@code findById} даёт и проверку существования
 * публикации (иначе {@code 404}), и саму сущность как цель связи маркера — поэтому
 * добавлять отдельную проверку существования не потребовалось (§4).</p>
 */
@Service
public class SavedPostingService {

    private final SavedPostingRepository markers;
    private final AppUserRepository users;
    private final PostingReadRepository postings;

    /**
     * @param markers  доступ к маркерам
     * @param users    резолв текущего пользователя по email из сессии
     * @param postings проверка существования публикации и получение её сущности
     */
    public SavedPostingService(SavedPostingRepository markers,
                               AppUserRepository users,
                               PostingReadRepository postings) {
        this.markers = markers;
        this.users = users;
        this.postings = postings;
    }

    /**
     * Пометить публикацию сохранённой (идемпотентно).
     *
     * @param authentication текущая аутентификация (владелец)
     * @param postingId      id публикации
     * @return представление маркера в состоянии {@link SavedState#SAVED}
     * @throws ResponseStatusException {@code 404}, если публикации нет
     */
    @Transactional
    public SavedPostingResponse save(Authentication authentication, Long postingId) {
        return upsert(authentication, postingId, SavedState.SAVED, null);
    }

    /**
     * Скрыть публикацию с необязательной причиной (идемпотентно).
     *
     * @param authentication текущая аутентификация (владелец)
     * @param postingId      id публикации
     * @param reason         причина скрытия или {@code null}
     * @return представление маркера в состоянии {@link SavedState#HIDDEN}
     * @throws ResponseStatusException {@code 404}, если публикации нет
     */
    @Transactional
    public SavedPostingResponse hide(Authentication authentication, Long postingId, String reason) {
        return upsert(authentication, postingId, SavedState.HIDDEN, reason);
    }

    /**
     * Снять маркер (отменить сохранение/скрытие). Идемпотентно: если маркера нет — ничего
     * не делает (тот же результат {@code 204} у контроллера).
     *
     * @param authentication текущая аутентификация (владелец)
     * @param postingId      id публикации
     */
    @Transactional
    public void remove(Authentication authentication, Long postingId) {
        AppUser owner = currentUser(authentication);
        markers.findByUser_IdAndPosting_Id(owner.getId(), postingId)
                .ifPresent(markers::delete);
    }

    /**
     * Сохранённые публикации текущего пользователя (новые сверху).
     *
     * @param authentication текущая аутентификация (владелец)
     * @return маркеры владельца в состоянии {@link SavedState#SAVED}
     */
    @Transactional(readOnly = true)
    public List<SavedPostingResponse> listSaved(Authentication authentication) {
        AppUser owner = currentUser(authentication);
        return markers.findByUser_IdAndStateOrderByIdDesc(owner.getId(), SavedState.SAVED)
                .stream()
                .map(SavedPostingService::toResponse)
                .toList();
    }

    /**
     * Найти-или-создать маркер владельца на публикации и выставить состояние/причину.
     *
     * <p>Идемпотентность (§3.4): повторный вызов обновляет существующую строку, а не создаёт
     * новую (уникальный ключ {@code (app_user_id, job_posting_id)} — durable-страховка).
     * Запись через сеттеры сущности (§17.3), а не длинным SQL. Причина хранится только у
     * скрытой публикации; при переходе в {@code SAVED} прежняя причина очищается.</p>
     */
    private SavedPostingResponse upsert(Authentication authentication, Long postingId,
                                        SavedState state, String reason) {
        AppUser owner = currentUser(authentication);
        JobPosting posting = postings.findById(postingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Публикация не найдена: " + postingId));

        SavedPosting marker = markers.findByUser_IdAndPosting_Id(owner.getId(), postingId)
                .orElseGet(SavedPosting::new);
        marker.setUser(owner);
        marker.setPosting(posting);
        marker.setState(state);
        marker.setHiddenReason(state == SavedState.HIDDEN ? reason : null);

        return toResponse(markers.save(marker));
    }

    /**
     * Текущий пользователь по аутентификации. Достижимо только для аутентифицированного
     * запроса (иначе цепочка безопасности вернёт {@code 401} до контроллера); проверка на
     * {@code null} — защита на случай прямого вызова в обход веб-слоя.
     */
    private AppUser currentUser(Authentication authentication) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход");
        }
        return users.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Сессия недействительна"));
    }

    /** Отображение сущности в DTO. {@code getPosting().getId()} по id прокси не грузит публикацию. */
    private static SavedPostingResponse toResponse(SavedPosting marker) {
        return new SavedPostingResponse(marker.getPosting().getId(), marker.getState(),
                marker.getHiddenReason(), marker.getCreatedAt());
    }
}
