package com.roleorienta.api.subscription;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.discovery.CompanyRepository;
import com.roleorienta.api.subscription.CompanySubscriptionDtos.SubscriptionResponse;
import com.roleorienta.core.domain.Company;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика подписок на компании (§7): подписаться, отписаться, перечислить — всегда в
 * пределах текущего пользователя.
 *
 * <p><b>Проверка владельца (A23, §3.9).</b> Владелец берётся только из аутентификации
 * (сессии): {@code Authentication.getName()} даёт email, по нему находится {@link AppUser}.
 * Идентификатор пользователя никогда не принимается из запроса, поэтому один пользователь
 * не видит и не меняет подписки другого. Три зависимости — в пределах лимита §3.10;
 * существующий {@link CompanyRepository} переиспользуется для проверки существования
 * компании (иначе {@code 404}) и как цель связи.</p>
 */
@Service
public class CompanySubscriptionService {

    private final CompanySubscriptionRepository subscriptions;
    private final AppUserRepository users;
    private final CompanyRepository companies;

    public CompanySubscriptionService(CompanySubscriptionRepository subscriptions,
                                      AppUserRepository users,
                                      CompanyRepository companies) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.companies = companies;
    }

    /**
     * Подписаться на компанию (идемпотентно): повторная подписка не создаёт вторую строку.
     *
     * @param authentication текущая аутентификация (владелец)
     * @param companyId      id компании
     * @return представление подписки
     * @throws ResponseStatusException {@code 404}, если компании нет
     */
    @Transactional
    public SubscriptionResponse subscribe(Authentication authentication, Long companyId) {
        AppUser owner = currentUser(authentication);
        Company company = companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Компания не найдена: " + companyId));

        CompanySubscription subscription = subscriptions
                .findByUser_IdAndCompany_Id(owner.getId(), companyId)
                .orElseGet(CompanySubscription::new);
        subscription.setUser(owner);
        subscription.setCompany(company);
        return SubscriptionResponse.of(subscriptions.save(subscription));
    }

    /**
     * Отписаться (идемпотентно): если подписки нет — ничего не делает.
     *
     * @param authentication текущая аутентификация (владелец)
     * @param companyId      id компании
     */
    @Transactional
    public void unsubscribe(Authentication authentication, Long companyId) {
        AppUser owner = currentUser(authentication);
        subscriptions.findByUser_IdAndCompany_Id(owner.getId(), companyId)
                .ifPresent(subscriptions::delete);
    }

    /**
     * Подписки текущего пользователя (новые сверху).
     *
     * @param authentication текущая аутентификация (владелец)
     * @return подписки владельца
     */
    @Transactional(readOnly = true)
    public List<SubscriptionResponse> list(Authentication authentication) {
        AppUser owner = currentUser(authentication);
        return subscriptions.findByUser_IdOrderByIdDesc(owner.getId())
                .stream()
                .map(SubscriptionResponse::of)
                .toList();
    }

    /**
     * Текущий пользователь по аутентификации. Достижимо только для аутентифицированного
     * запроса (иначе цепочка безопасности вернёт {@code 401} до контроллера); проверка
     * на {@code null} — защита на случай прямого вызова в обход веб-слоя.
     */
    private AppUser currentUser(Authentication authentication) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход");
        }
        return users.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Сессия недействительна"));
    }
}
