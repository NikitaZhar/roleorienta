package com.roleorienta.api.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Учётная запись (бизнес-описание §6): email — имя для входа, хранится в нижнем регистре; пароль —
 * только хеш.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String passwordHash;

    /**
     * Для JPA.
     */
    protected UserAccount() {
    }

    /**
     * @param email        email в нижнем регистре
     * @param passwordHash хеш пароля ({@code PasswordEncoder})
     */
    public UserAccount(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
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
}
