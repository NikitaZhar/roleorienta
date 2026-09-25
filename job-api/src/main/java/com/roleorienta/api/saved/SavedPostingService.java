package com.roleorienta.api.saved;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.saved.SavedPostingDtos.SavedPostingResponse;
import com.roleorienta.core.domain.JobPosting;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика персональных маркеров публикаций (§7): сохранить, скрыть, отметить просмотренной,
 * снять и перечислить сохранённые — всегда в пределах текущего пользователя.
 *
 * <p><b>Проверка владельца (A23, §3.9).</b> Владелец берётся только из аутентификации
 * (сессии): {@code Authentication.getName()} даёт email, по нему находится {@link AppUser}.
 * Идентификатор пользователя никогда не принимается из запроса, поэтому один пользователь
 * не может прочитать или изменить маркер другого — все операции идут по id владельца.</p>
 *
 * <p>Слои соблюдены (§3.3): сервис содержит логику, репозитории — только доступ. Три
 * зависимости — в пределах лимита §3.10. {@link MarkedPostingRepository#findById} даёт и проверку
 * существования публикации (иначе {@code 404}), и саму сущность как цель связи маркера.</p>
 */
@Service
public class SavedPostingService {

    private final SavedPostingRepository markers;
    private final AppUserRepository users;
    private final MarkedPostingRepository postings;

    /**
     * @param markers  доступ к маркерам
     * @param users    резолв текущего пользователя по email из сессии
     * @param postings проверка существования публикации и получение её сущности
     */
    public SavedPostingService(SavedPostingRepository markers,
                               AppUserRepository users,
                               MarkedPostingRepository postings) {
        this.markers = markers;
        this.users = users;
        this.postings = postings;
    }

    /**
     * Пометить публикацию сохранённой (идемпотентно). Признак просмотра, если был, сохраняется.
     *
     * @param authentication текущая аутентификация (владелец)
     * @param postingId      id публикации
     * @return представление маркера в состоянии {@link SavedState#SAVED}
     * @throws ResponseStatusException {@code 404}, если публикации нет
     */
    @Transactional
    public SavedPostingResponse save(Authentication authentication, Long postingId) {
        return upsert(authentication, postingId, marker -> {
            marker.setState(SavedState.SAVED);
            marker.setHiddenReason(null);
        });
    }

    /**
     * Скрыть публикацию с необязательной причиной (идемпотентно). Признак просмотра сохраняется.
     *
     * @param authentication текущая аутентификация (владелец)
     * @param postingId      id публикации
     * @param reason         причина скрытия или {@code null}
     * @return представление маркера в состоянии {@link SavedState#HIDDEN}
     * @throws ResponseStatusException {@code 404}, если публикации нет
     */
    @Transactional
    public SavedPostingResponse hide(Authentication authentication, Long postingId, String reason) {
        return upsert(authentication, postingId, marker -> {
            marker.setState(SavedState.HIDDEN);
            marker.setHiddenReason(reason);
        });
    }

    /**
     * Отметить публикацию просмотренной (идемпотентно): проставляет момент первого просмотра,
     * если он ещё не задан. Сохранение/скрытие, если было, не меняется.
     *
     * @param authentication текущая аутентификация (владелец)
     * @param postingId      id публикации
     * @return представление маркера с проставленным {@code seenAt}
     * @throws ResponseStatusException {@code 404}, если публикации нет
     */
    @Transactional
    public SavedPostingResponse markSeen(Authentication authentication, Long postingId) {
        return upsert(authentication, postingId, marker -> {
            if (marker.getSeenAt() == null) {
                marker.setSeenAt(Instant.now());
            }
        });
    }

    /**
     * Снять сохранение/скрытие. Признак просмотра сохраняется: если публикация была
     * просмотрена, строка остаётся (только с {@code seenAt}); иначе маркер удаляется целиком.
     * Идемпотентно: если маркера нет — ничего не делает.
     *
     * @param authentication текущая аутентификация (владелец)
     * @param postingId      id публикации
     */
    @Transactional
    public void remove(Authentication authentication, Long postingId) {
        AppUser owner = currentUser(authentication);
        markers.findByUser_IdAndPosting_Id(owner.getId(), postingId).ifPresent(marker -> {
            marker.setState(null);
            marker.setHiddenReason(null);
            if (marker.getSeenAt() == null) {
                markers.delete(marker);
            } else {
                markers.save(marker);
            }
        });
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
     * Найти-или-создать маркер владельца на публикации, применить изменение и сохранить.
     *
     * <p>Идемпотентность (§3.4): повторный вызов обновляет существующую строку, а не создаёт
     * новую (уникальный ключ {@code (app_user_id, job_posting_id)} — durable-страховка).
     * Запись через сеттеры сущности (§17.3), а не длинным SQL.</p>
     */
    private SavedPostingResponse upsert(Authentication authentication, Long postingId,
                                        Consumer<SavedPosting> change) {
        AppUser owner = currentUser(authentication);
        JobPosting posting = postings.findById(postingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Публикация не найдена: " + postingId));

        SavedPosting marker = markers.findByUser_IdAndPosting_Id(owner.getId(), postingId)
                .orElseGet(SavedPosting::new);
        marker.setUser(owner);
        marker.setPosting(posting);
        change.accept(marker);

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
                marker.getHiddenReason(), marker.getSeenAt(), marker.getCreatedAt());
    }
}
