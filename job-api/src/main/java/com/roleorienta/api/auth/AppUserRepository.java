package com.roleorienta.api.auth;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Доступ к учётным записям (§9).
 *
 * <p>Наследует узкий {@link Repository} и объявляет только нужные операции — в духе
 * {@code PostingReadRepository}. В отличие от чтения публикаций, здесь есть запись
 * ({@link #save}): регистрация и bootstrap администратора создают пользователей.
 * Поиск и проверки по email — без учёта регистра (совпадает с уникальным индексом
 * по {@code lower(email)}, V14).</p>
 */
public interface AppUserRepository extends Repository<AppUser, Long> {

    /**
     * Найти пользователя по email без учёта регистра.
     *
     * @param email email пользователя
     * @return пользователь или пустое значение, если такого нет
     */
    Optional<AppUser> findByEmailIgnoreCase(String email);

    /**
     * Есть ли пользователь с таким email (без учёта регистра).
     *
     * @param email email для проверки
     * @return {@code true}, если пользователь существует
     */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Есть ли хотя бы один пользователь с указанной ролью.
     *
     * @param role роль
     * @return {@code true}, если такой пользователь существует
     */
    boolean existsByRole(UserRole role);

    /**
     * Сохранить пользователя (создание при регистрации/bootstrap).
     *
     * @param user сохраняемый пользователь
     * @return сохранённая сущность с присвоенным {@code id}
     */
    AppUser save(AppUser user);
}
