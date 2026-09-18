package com.roleorienta.api.saved;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Доступ к персональным маркерам публикаций (§7).
 *
 * <p>Наследует узкий {@link Repository} (а не {@code JpaRepository}) — в духе
 * {@code AppUserRepository}/{@code PostingReadRepository}: объявлены только нужные
 * операции. Есть запись ({@link #save}) и удаление ({@link #delete}): пользователь ставит
 * и снимает маркеры. Все выборки идут по владельцу — id пользователя всегда задаёт сервис
 * из сессии, а не клиент (§3.9, A23).</p>
 */
public interface SavedPostingRepository extends Repository<SavedPosting, Long> {

    /**
     * Найти маркер пользователя на публикации (для идемпотентного upsert).
     *
     * @param userId    id владельца
     * @param postingId id публикации
     * @return маркер или пустое значение, если пользователь ещё не помечал публикацию
     */
    Optional<SavedPosting> findByUser_IdAndPosting_Id(Long userId, Long postingId);

    /**
     * Маркеры пользователя на заданном наборе публикаций (для аннотации ленты, §31).
     *
     * @param userId     id владельца
     * @param postingIds идентификаторы публикаций текущей страницы ленты
     * @return маркеры владельца по этим публикациям (для отсутствующих строк нет)
     */
    List<SavedPosting> findByUser_IdAndPosting_IdIn(Long userId, List<Long> postingIds);

    /**
     * Маркеры пользователя в заданном состоянии, новые сверху (по убыванию {@code id}).
     *
     * @param userId id владельца
     * @param state  искомое состояние (например {@link SavedState#SAVED})
     * @return список маркеров владельца в этом состоянии
     */
    List<SavedPosting> findByUser_IdAndStateOrderByIdDesc(Long userId, SavedState state);

    /**
     * Сохранить маркер (создание или обновление состояния/причины/просмотра).
     *
     * @param marker маркер
     * @return сохранённая сущность
     */
    SavedPosting save(SavedPosting marker);

    /**
     * Удалить маркер (когда не осталось ни сохранения/скрытия, ни просмотра).
     *
     * @param marker маркер
     */
    void delete(SavedPosting marker);
}
