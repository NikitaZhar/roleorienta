package com.roleorienta.worker.intake;

import java.time.LocalDate;

/**
 * Юрлицо, как его дал реестр.
 *
 * @param registrationNumber регистрационный номер (IČO)
 * @param name               название
 * @param legalForm          правовая форма; {@code null} — не указана
 * @param municipality       населённый пункт адреса; {@code null} — не указан
 * @param terminatedOn       дата прекращения; {@code null} — действует
 */
public record RegistryCompany(String registrationNumber, String name, String legalForm, String municipality,
        LocalDate terminatedOn) {
}
