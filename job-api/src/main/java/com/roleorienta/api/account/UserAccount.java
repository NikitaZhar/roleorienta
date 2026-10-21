package com.roleorienta.api.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Учётная запись (бизнес-описание §6): email — имя для входа, хранится в нижнем регистре; пароль —
 * только хеш; язык интерфейса — {@code en} или {@code ru} (§162); частота сводки на почту и часовой пояс (§164).
 * Отметку отработанной сводки ({@code digest_day}) и вакансии, попавшие в письмо ({@code digest_vacancy}), пишет
 * job-worker — здесь их нет.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String passwordHash;

    private String language;

    private String digestFrequency;

    private String timeZone;

    /**
     * Для JPA.
     */
    protected UserAccount() {
    }

    /**
     * @param email        email в нижнем регистре
     * @param passwordHash хеш пароля ({@code PasswordEncoder})
     * @param language     язык интерфейса: {@code en} или {@code ru}
     * @param timeZone     часовой пояс (IANA) — время сводки на почту; частота сводки — по умолчанию, каждый день
     */
    public UserAccount(String email, String passwordHash, String language, String timeZone) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.language = language;
        this.timeZone = timeZone;
        this.digestFrequency = "DAILY";
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getDigestFrequency() {
        return digestFrequency;
    }

    public void setDigestFrequency(String digestFrequency) {
        this.digestFrequency = digestFrequency;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }
}
