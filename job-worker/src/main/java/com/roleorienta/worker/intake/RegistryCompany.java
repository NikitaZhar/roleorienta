package com.roleorienta.worker.intake;

import java.time.LocalDate;

/**
 * Юрлицо, как его дал реестр.
 *
 * @param registrationNumber регистрационный номер (IČO)
 * @param name               название
 * @param details            правовая форма, населённый пункт, признак кадрового агентства
 * @param terminatedOn       дата прекращения; {@code null} — действует
 */
public record RegistryCompany(String registrationNumber, String name, Details details, LocalDate terminatedOn) {

    /**
     * @param legalForm    правовая форма; {@code null} — не указана
     * @param municipality населённый пункт адреса; {@code null} — не указан
     * @param agency       кадровое агентство: основной вид деятельности SK NACE 78 (агентства занятости)
     */
    public record Details(String legalForm, String municipality, boolean agency) {
    }
}
