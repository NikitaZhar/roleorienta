package com.roleorienta.api.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Вход и доступ (технический документ §8, §10, §16.17).
 *
 * <ul>
 *   <li>Без входа открыты регистрация, вход, справочники стран и позиций, health; остальное —
 *       {@code 401} в формате problem+json.</li>
 *   <li>Вход хранится в HTTP-сессии (сессии — в PostgreSQL, Spring Session JDBC); cookie сессии —
 *       {@code HttpOnly}, {@code SameSite=Lax}, {@code Secure} настройкой.</li>
 *   <li>CSRF для SPA ({@code csrf.spa()}): токен в cookie {@code XSRF-TOKEN}, SPA возвращает его
 *       заголовком {@code X-XSRF-TOKEN} в {@code POST}/{@code PUT}/{@code DELETE}; без него — {@code 403}.
 *       Токен создаётся лениво — фильтр {@link CsrfCookieFilter} создаёт его на каждом запросе, чтобы
 *       cookie была у SPA до первого изменяющего запроса.</li>
 *   <li>Пароли — BCrypt (хеш с префиксом алгоритма, {@code {bcrypt}…}).</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private static final String UNAUTHORIZED_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Sign in required\"}";

    /**
     * @param http             настройка цепочки фильтров
     * @param securityContexts хранение входа в сессии
     * @return цепочка фильтров API
     * @throws Exception ошибка настройки Spring Security
     */
    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, SecurityContextRepository securityContexts)
            throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/countries", "/api/v1/positions").permitAll()
                        .requestMatchers("/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .securityContext(context -> context.securityContextRepository(securityContexts))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(SecurityConfig::unauthorized))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
        return http.build();
    }

    /**
     * @return хеширование паролей: BCrypt с префиксом алгоритма (смена алгоритма без миграции)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * @return хранение входа в HTTP-сессии
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * @param accounts учётные записи
     * @return пользователь для проверки пароля по email
     */
    @Bean
    public UserDetailsService userDetailsService(UserAccountRepository accounts) {
        return email -> accounts.findByEmail(AccountService.normalize(email))
                .map(account -> User.withUsername(account.getEmail()).password(account.getPasswordHash())
                        .roles("USER").build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }

    /**
     * @param users           пользователи по email
     * @param passwordEncoder хеширование паролей
     * @return проверка email и пароля при входе
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    private static void unauthorized(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }

    /**
     * Создаёт отложенный CSRF-токен на каждом запросе — репозиторий записывает его в cookie
     * {@code XSRF-TOKEN}.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
