package com.roleorienta.api.condition;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Версия условий поиска (бизнес-описание §3; технический документ §4, §7). Смена стран, позиции
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

    private long positionId;

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
     * @param positionId   позиция
     * @param workFormat   формат; {@code null} — не задан
     * @param portionLimit лимит порции
     */
    public SearchCondition(long userId, List<String> countries, long positionId, WorkFormat workFormat,
            int portionLimit) {
        this.userId = userId;
        this.countries = countries;
        this.positionId = positionId;
        this.workFormat = workFormat;
        this.portionLimit = portionLimit;
        this.active = true;
    }

    /**
     * @param otherCountries  страны, отсортированные
     * @param otherPositionId позиция
     * @param otherFormat     формат
     * @return отбор тот же — новая версия не нужна
     */
    public boolean sameSelection(List<String> otherCountries, long otherPositionId, WorkFormat otherFormat) {
        return countries.equals(otherCountries) && positionId == otherPositionId
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

    public long getPositionId() {
        return positionId;
    }

    public WorkFormat getWorkFormat() {
        return workFormat;
    }

    public int getPortionLimit() {
        return portionLimit;
    }
}
