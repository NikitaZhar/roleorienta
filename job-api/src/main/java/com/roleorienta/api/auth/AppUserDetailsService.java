package com.roleorienta.api.auth;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Загрузка пользователя для аутентификации Spring Security (§9).
 *
 * <p>Мост между нашей моделью ({@link AppUser}) и контрактом Spring Security:
 * находит пользователя по email и отдаёт {@link UserDetails} с хэшем пароля и
 * ролью. Само сравнение пароля выполняет {@code DaoAuthenticationProvider} через
 * {@code PasswordEncoder} — здесь пароль не проверяется. Роль отображается в
 * authority с префиксом {@code ROLE_} (соглашение Spring Security для
 * {@code hasRole}). https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/user-details-service.html</p>
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    /**
     * @param users доступ к учётным записям
     */
    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    /**
     * Найти пользователя по email (username) для аутентификации.
     *
     * @param username email пользователя
     * @return данные пользователя для Spring Security
     * @throws UsernameNotFoundException если пользователя с таким email нет
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByEmailIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }
}
