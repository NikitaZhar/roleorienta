package com.roleorienta.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Конфигурация безопасности job-api (§9).
 *
 * <p>Что задаёт эта конфигурация:</p>
 * <ul>
 *   <li><b>Доступ.</b> Публичные чтения ({@code GET /api/v1/postings/**}),
 *       health и эндпоинты входа/регистрации/получения CSRF-токена открыты; всё
 *       прочее требует аутентификации. Существующие маршруты чтения не меняются
 *       (§3.6/§7) — они лишь явно помечены открытыми.</li>
 *   <li><b>Сессия и cookie.</b> Аутентификация хранится в HTTP-сессии (сессия — в
 *       PostgreSQL, §9); атрибуты cookie (httpOnly/Secure/SameSite) заданы в
 *       application.yml.</li>
 *   <li><b>CSRF.</b> Токен кладётся в читаемую JavaScript cookie {@code XSRF-TOKEN}
 *       ({@link CookieCsrfTokenRepository#withHttpOnlyFalse()}); SPA возвращает его
 *       заголовком {@code X-XSRF-TOKEN} на изменяющих запросах. Фильтр
 *       {@link CsrfCookieFilter} принудительно отдаёт токен, т.к. Spring Security
 *       по умолчанию откладывает его вычисление и cookie иначе не выставляется.
 *       Клиент получает токен первым безопасным GET (например
 *       {@code GET /api/v1/auth/csrf}).
 *       https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html</li>
 *   <li><b>Точка входа.</b> Неаутентифицированный запрос к защищённому ресурсу даёт
 *       {@code 401} (для API), а не редирект на форму: {@link HttpStatusEntryPoint}.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    /**
     * Кодировщик паролей: делегирующий энкодер Spring Security (по умолчанию bcrypt),
     * хранит хэш с префиксом алгоритма ({@code {bcrypt}...}) — можно менять алгоритм
     * без миграции старых хэшей.
     *
     * @return делегирующий {@link PasswordEncoder}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Менеджер аутентификации по логину/паролю: {@link DaoAuthenticationProvider}
     * поверх {@link AppUserDetailsService} и {@link PasswordEncoder}. Используется
     * эндпоинтом входа.
     *
     * @param userDetailsService загрузка пользователя по email
     * @param passwordEncoder    сверка пароля с хэшем
     * @return менеджер аутентификации
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /**
     * Цепочка фильтров безопасности: доступ, CSRF, точка входа 401 и logout.
     *
     * @param http построитель конфигурации HTTP-безопасности
     * @return собранная цепочка фильтров
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/postings/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfRequestHandler))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request, response, authentication) ->
                        response.setStatus(HttpStatus.NO_CONTENT.value()))
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("SESSION"));

        return http.build();
    }

    /**
     * Принудительно отдаёт CSRF-токен, чтобы cookie {@code XSRF-TOKEN} выставлялась.
     *
     * <p>Spring Security откладывает вычисление токена (защита от BREACH); без обращения
     * к нему {@link CookieCsrfTokenRepository} не запишет cookie. Фильтр читает токен из
     * атрибута запроса и вызывает {@link CsrfToken#getToken()} — этого достаточно, чтобы
     * репозиторий отдал cookie. Ставится сразу после {@link CsrfFilter}.</p>
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
