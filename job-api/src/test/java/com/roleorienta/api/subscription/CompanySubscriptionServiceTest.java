package com.roleorienta.api.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.api.auth.AppUserRepository;
import com.roleorienta.api.discovery.CompanyRepository;
import com.roleorienta.api.subscription.CompanySubscriptionDtos.SubscriptionResponse;
import com.roleorienta.core.domain.Company;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика подписок на компании (§37): резолв владельца из сессии (A23), идемпотентность,
 * 404 на несуществующую компанию. Репозитории и аутентификация — заглушки (без БД).
 */
class CompanySubscriptionServiceTest {

    private final CompanySubscriptionRepository subscriptions = mock(CompanySubscriptionRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final CompanySubscriptionService service =
            new CompanySubscriptionService(subscriptions, users, companies);

    private final Authentication auth = mock(Authentication.class);
    private final AppUser owner = mock(AppUser.class);
    private final Company company = mock(Company.class);

    private void withOwnerAndCompany() {
        when(auth.getName()).thenReturn("u@example.com");
        when(owner.getId()).thenReturn(1L);
        when(users.findByEmailIgnoreCase("u@example.com")).thenReturn(Optional.of(owner));
        when(company.getId()).thenReturn(10L);
        when(company.getName()).thenReturn("Acme");
        when(companies.findById(10L)).thenReturn(Optional.of(company));
    }

    @Test
    void subscribeCreatesWhenAbsent() {
        withOwnerAndCompany();
        when(subscriptions.findByUser_IdAndCompany_Id(1L, 10L)).thenReturn(Optional.empty());
        when(subscriptions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionResponse response = service.subscribe(auth, 10L);

        ArgumentCaptor<CompanySubscription> captor = ArgumentCaptor.forClass(CompanySubscription.class);
        verify(subscriptions).save(captor.capture());
        assertEquals(owner, captor.getValue().getUser());
        assertEquals(company, captor.getValue().getCompany());
        assertEquals(10L, response.companyId());
        assertEquals("Acme", response.companyName());
    }

    @Test
    void subscribeMissingCompanyIsNotFound() {
        when(auth.getName()).thenReturn("u@example.com");
        when(owner.getId()).thenReturn(1L);
        when(users.findByEmailIgnoreCase("u@example.com")).thenReturn(Optional.of(owner));
        when(companies.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.subscribe(auth, 999L));
        verify(subscriptions, never()).save(any());
    }

    @Test
    void unsubscribeDeletesWhenPresent() {
        withOwnerAndCompany();
        CompanySubscription existing = new CompanySubscription();
        when(subscriptions.findByUser_IdAndCompany_Id(1L, 10L)).thenReturn(Optional.of(existing));

        service.unsubscribe(auth, 10L);

        verify(subscriptions).delete(existing);
    }

    @Test
    void unsubscribeIsIdempotentWhenAbsent() {
        withOwnerAndCompany();
        when(subscriptions.findByUser_IdAndCompany_Id(1L, 10L)).thenReturn(Optional.empty());

        service.unsubscribe(auth, 10L);

        verify(subscriptions, never()).delete(any());
    }

    @Test
    void listReturnsOwnerSubscriptions() {
        withOwnerAndCompany();
        CompanySubscription sub = new CompanySubscription();
        sub.setCompany(company);
        when(subscriptions.findByUser_IdOrderByIdDesc(1L)).thenReturn(List.of(sub));

        List<SubscriptionResponse> list = service.list(auth);

        assertEquals(1, list.size());
        assertEquals(10L, list.get(0).companyId());
    }
}
