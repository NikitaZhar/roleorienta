package com.roleorienta.api.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к учётным записям.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * @param email email в нижнем регистре
     * @return учётная запись
     */
    Optional<UserAccount> findByEmail(String email);

    /**
     * @param email email в нижнем регистре
     * @return есть ли учётная запись
     */
    boolean existsByEmail(String email);

    /**
     * Блокирует строку пользователя до конца транзакции ({@code SELECT … FOR UPDATE}): изменения одного пользователя,
     * которые читают и затем пишут (смена статуса вакансии, лимит предложений позиций), идут по одной (контракт §3.5).
     * Одна блокировка на запрос — взаимной блокировки нет.
     *
     * @param id пользователь
     * @return он же; {@code null} — учётной записи нет
     */
    @Query(value = "SELECT id FROM user_account WHERE id = :id FOR UPDATE", nativeQuery = true)
    Long lock(@Param("id") long id);

    /**
     * Часовой пояс известен PostgreSQL по названию ({@code pg_timezone_names}): по нему job-worker считает 8:00
     * пользователя ({@code AT TIME ZONE}). Проверка — там, где пояс применяется, а не в Java: смещения вида
     * {@code +02:00} PostgreSQL читает со знаком наоборот, а названия, которых нет в его базе поясов, уронили бы
     * расчёт сводки для всех (аудит §165).
     *
     * @param name название (IANA)
     * @return {@code true} — известен
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = :name)", nativeQuery = true)
    boolean knownTimeZone(@Param("name") String name);
}
