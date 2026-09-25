package com.roleorienta.api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Бизнес-логика учётных записей (§9): регистрация и создание администратора.
 *
 * <p>Единственное место, где пароль превращается в хэш и проверяется уникальность
 * email. Контроллер сюда делегирует и сам логики не содержит (§3.3). Открытый
 * пароль в системе не сохраняется — только результат {@link PasswordEncoder}
 * (§3.9).</p>
 */
@Service
public class AppUserService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    /**
     * @param users           доступ к учётным записям
     * @param passwordEncoder кодировщик паролей (bcrypt через делегирующий энкодер)
     */
    public AppUserService(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Зарегистрировать нового пользователя с ролью {@link UserRole#USER}.
     *
     * <p>Транзакция охватывает проверку уникальности и вставку; уникальный индекс по
     * {@code lower(email)} (V14) — вторая линия защиты от гонки при параллельной
     * регистрации одного email.</p>
     *
     * @param email       email нового пользователя
     * @param rawPassword открытый пароль (будет захэширован, не сохраняется как есть)
     * @return созданный пользователь
     * @throws ResponseStatusException {@code 409 CONFLICT}, если email уже занят
     */
    @Transactional
    public AppUser register(String email, String rawPassword) {
        return create(email, rawPassword, UserRole.USER);
    }

    /**
     * Создать администратора при bootstrap, если пользователя с таким email ещё нет.
     *
     * <p>Идемпотентно по email (§3.4): повторный старт с тем же адресом не создаёт
     * дубликат и не меняет существующего пользователя.</p>
     *
     * @param email       email администратора
     * @param rawPassword открытый пароль администратора
     * @return {@code true}, если администратор был создан; {@code false}, если уже существовал
     */
    @Transactional
    public boolean createAdminIfAbsent(String email, String rawPassword) {
        if (users.existsByEmailIgnoreCase(email)) {
            return false;
        }
        create(email, rawPassword, UserRole.ADMIN);
        return true;
    }

    /**
     * Текущий пользователь по email из аутентификации (сессии).
     *
     * @param email email владельца сессии
     * @return пользователь
     * @throws ResponseStatusException {@code 401 UNAUTHORIZED}, если пользователя уже нет
     *                                 (удалён после входа) — сессия недействительна
     */
    @Transactional(readOnly = true)
    public AppUser current(String email) {
        return users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Сессия недействительна"));
    }

    private AppUser create(String email, String rawPassword, UserRole role) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email уже используется");
        }
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        return users.save(user);
    }
}
