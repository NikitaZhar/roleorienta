package com.roleorienta.api.condition;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Позиция словаря (технический документ §6) для условий поиска — только чтение; словарь правит администратор
 * ({@code DictionaryService}, §160). Выключенная позиция не предлагается при выборе, но остаётся в условиях, где её
 * уже выбрали.
 */
@Entity
@Table(name = "position")
public class Position {

    @Id
    private Long id;

    private String code;

    private String name;

    private boolean active;

    /**
     * Для JPA.
     */
    protected Position() {
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}
