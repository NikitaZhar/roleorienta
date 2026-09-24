package com.roleorienta.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roleorienta.worker.adapters.BoardProfile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Проверка принадлежности доски (A2, §79): тенант назван в описании — подтверждено; нет
 * описания, признак агентства или описание не называет тенант — сомнение.
 */
class BoardOwnershipPropertiesTest {

    private final BoardOwnershipProperties ownership =
            new BoardOwnershipProperties(List.of("staffing", "Personalvermittlung"));

    @Test
    void tenantNamedInDescriptionConfirmsOwner() {
        assertEquals(Optional.empty(), ownership.doubt(new BoardProfile("iqvia",
                "IQVIA is a leading global provider of clinical research services.")));
        assertEquals(Optional.empty(), ownership.doubt(new BoardProfile("tobiidynavox",
                "At Tobii Dynavox we empower people with disabilities.")));
    }

    @Test
    void missingDescriptionIsDoubt() {
        assertTrue(ownership.doubt(new BoardProfile("iqvia", "")).orElseThrow()
                .startsWith("владелец доски не подтверждён"));
        assertTrue(ownership.doubt(new BoardProfile("iqvia", null)).isPresent());
    }

    @Test
    void agencyMarkerIsDoubtEvenWhenTenantNamed() {
        assertTrue(ownership.doubt(new BoardProfile("randstad",
                "Randstad ist Ihr Partner für Personalvermittlung.")).orElseThrow()
                .startsWith("похоже на кадровое агентство"));
    }

    @Test
    void descriptionNotNamingTenantIsDoubt() {
        assertTrue(ownership.doubt(new BoardProfile("jnj",
                "Johnson & Johnson is a healthcare company.")).orElseThrow()
                .startsWith("описание доски не называет владельца"));
    }
}
