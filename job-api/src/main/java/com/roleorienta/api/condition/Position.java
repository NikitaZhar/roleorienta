package com.roleorienta.api.condition;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Позиция словаря (технический документ §6); таблицу заполняет job-worker из словаря с версией,
 * здесь — только чтение.
 */
@Entity
@Table(name = "position")
public class Position {

    @Id
    private Long id;

    private String code;

    private String name;

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
}
