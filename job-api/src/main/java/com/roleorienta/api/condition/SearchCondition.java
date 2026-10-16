package com.roleorienta.api.condition;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Версия условий поиска (бизнес-описание §3; технический документ §4, §7). Смена стран, позиций
 * или формата — новая версия с пустым накопленным списком; лимит меняется в той же версии. Активна
 * одна версия на пользователя; выдачу по ней делает job-worker.
 */
@Entity
@Table(name = "search_condition")
public class SearchCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long userId;

    /** Массив PostgreSQL {@code TEXT[]}; страны отсортированы. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> countries;

    /**
     * Позиции версии (от одной; §126) — таблица {@code search_condition_position}. {@code @ElementCollection} —
     * коллекция простых значений сущности в своей таблице, без отдельной сущности
     * (https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2#a2129): JPA читает и пишет её
     * строки вместе с версией.
     */
    @ElementCollection
    @CollectionTable(name = "search_condition_position", joinColumns = @JoinColumn(name = "search_condition_id"))
    @Column(name = "position_id")
    private Set<Long> positionIds = new HashSet<>();

    @Enumerated(EnumType.STRING)
    private WorkFormat workFormat;

    private int portionLimit;

    private boolean active;

    /**
     * Для JPA.
     */
    protected SearchCondition() {
    }

    /**
     * Новая активная версия.
     *
     * @param userId       пользователь
     * @param countries    страны, отсортированные, без повторов
     * @param positionIds  позиции, хотя бы одна
     * @param workFormat   формат; {@code null} — не задан
     * @param portionLimit лимит порции
     */
    public SearchCondition(long userId, List<String> countries, Set<Long> positionIds, WorkFormat workFormat,
            int portionLimit) {
        this.userId = userId;
        this.countries = countries;
        this.positionIds = new HashSet<>(positionIds);
        this.workFormat = workFormat;
        this.portionLimit = portionLimit;
        this.active = true;
    }

    /**
     * @param otherCountries  страны, отсортированные
     * @param otherPositionIds позиции
     * @param otherFormat     формат
     * @return отбор тот же (тот же набор позиций, порядок не важен) — новая версия не нужна
     */
    public boolean sameSelection(List<String> otherCountries, Set<Long> otherPositionIds, WorkFormat otherFormat) {
        return countries.equals(otherCountries) && Set.copyOf(positionIds).equals(otherPositionIds)
                && Objects.equals(workFormat, otherFormat);
    }

    /**
     * @return версия для {@code ETag}/{@code If-Match}: id версии и лимит — меняется при любом
     *         изменении условий
     */
    public String etag() {
        return "\"" + id + "." + portionLimit + "\"";
    }

    /**
     * Версия заменена новой.
     */
    public void deactivate() {
        this.active = false;
    }

    public void setPortionLimit(int portionLimit) {
        this.portionLimit = portionLimit;
    }

    public Long getId() {
        return id;
    }

    public List<String> getCountries() {
        return countries;
    }

    public Set<Long> getPositionIds() {
        return positionIds;
    }

    public WorkFormat getWorkFormat() {
        return workFormat;
    }

    public int getPortionLimit() {
        return portionLimit;
    }
}
