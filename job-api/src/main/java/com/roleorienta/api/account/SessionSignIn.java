package com.roleorienta.api.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Сохраняет проверенный вход в HTTP-сессии — общий шаг входа пользователя и администратора (§132): id сессии
 * меняется (защита от подмены сессии), вход записывается в сессию.
 */
@Component
public class SessionSignIn {

    private final SecurityContextRepository securityContexts;

    /**
     * @param securityContexts хранение входа в HTTP-сессии
     */
    public SessionSignIn(SecurityContextRepository securityContexts) {
        this.securityContexts = securityContexts;
    }

    /**
     * @param authentication проверенный вход
     * @param request        запрос
     * @param response       ответ
     */
    public void signIn(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
    }
}
