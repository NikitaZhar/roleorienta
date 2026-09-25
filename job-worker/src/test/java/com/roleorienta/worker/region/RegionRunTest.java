package com.roleorienta.worker.region;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.region.RegistryCompanyStore.Imported;
import com.roleorienta.worker.region.RegistryCompanyStore.Pending;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Проход «от региона» (§95): импорт только профиля и в пределах выборки, затем проверка пачки. */
class RegionRunTest {

    private final RpoClient registry = mock(RpoClient.class);
    private final RegistryCompanyStore store = mock(RegistryCompanyStore.class);
    private final CompanyCheck check = mock(CompanyCheck.class);

    private RegionRun run(int importLimit) {
        return new RegionRun(new RegionProperties(true, new RegionProperties.Rpo("https://rpo.test",
                List.of("programovanie"), List.of("62"), List.of("Bratislava")), importLimit, 5, 60),
                registry, store, check, mock(PostgresLeaderLock.class));
    }

    @Test
    void importsOnlyCompaniesWithProfileActivityAndChecksBatch() {
        when(store.count("SK")).thenReturn(0);
        when(store.exists(anyString(), anyString(), anyString())).thenReturn(false);
        when(store.add(any())).thenReturn(true);
        when(registry.search("programovanie", "Bratislava")).thenReturn(List.of(
                new RpoClient.Hit(1, "11111111", "Soft s.r.o.", "Bratislava"),
                new RpoClient.Hit(2, "22222222", "Pekáreň s.r.o.", "Bratislava")));
        when(registry.mainActivity(1)).thenReturn(Optional.of(new RpoClient.Activity("6210", "Programovanie")));
        when(registry.mainActivity(2)).thenReturn(Optional.of(new RpoClient.Activity("1071", "Pekárstvo")));
        Pending soft = new Pending(10, "11111111", "Soft s.r.o.");
        when(store.unchecked(5)).thenReturn(List.of(soft));
        when(check.check(soft)).thenReturn(new CheckResult(null, null, "сайт не найден"));

        run(50).runOnce();

        verify(store).add(new Imported("SK", "RPO", "11111111", "Soft s.r.o.",
                new RegistryCompanyStore.Details("Bratislava", "6210", "Programovanie")));
        verify(store, never()).add(new Imported("SK", "RPO", "22222222", "Pekáreň s.r.o.",
                new RegistryCompanyStore.Details("Bratislava", "1071", "Pekárstvo")));
        verify(store).recordCheck(eq(10L), any(CheckResult.class));
    }

    @Test
    void fullSampleImportsNothing() {
        when(store.count("SK")).thenReturn(50);
        when(store.unchecked(anyInt())).thenReturn(List.of());

        run(50).runOnce();

        verify(registry, never()).search(anyString(), anyString());
        verify(registry, never()).mainActivity(anyLong());
    }

    @Test
    void unreadableCardIsSkippedAndImportGoesOn() {
        when(store.count("SK")).thenReturn(0);
        when(store.exists(anyString(), anyString(), anyString())).thenReturn(false);
        when(store.add(any())).thenReturn(true);
        when(store.unchecked(anyInt())).thenReturn(List.of());
        when(registry.search("programovanie", "Bratislava")).thenReturn(List.of(
                new RpoClient.Hit(1, "11111111", "Pomalá s.r.o.", "Bratislava"),
                new RpoClient.Hit(2, "22222222", "Soft s.r.o.", "Bratislava")));
        when(registry.mainActivity(1)).thenThrow(new IllegalStateException("Read timed out"));
        when(registry.mainActivity(2)).thenReturn(Optional.of(new RpoClient.Activity("6210", "Programovanie")));

        run(50).runOnce();

        verify(store).add(new Imported("SK", "RPO", "22222222", "Soft s.r.o.",
                new RegistryCompanyStore.Details("Bratislava", "6210", "Programovanie")));
    }
}
