package com.roleorienta.worker.source;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * Источник вакансий — подключённая кадровая страница: провайдер (формат), доска у провайдера и
 * страна области чтения (ISO 3166-1 alpha-2; {@code null} — без фильтра).
 *
 * <p>JPA-сущность: {@link Entity} связывает класс с таблицей {@code source};
 * {@link GeneratedValue} с {@code IDENTITY} — id выдаёт PostgreSQL ({@code BIGSERIAL}).
 * https://jakarta.ee/specifications/persistence/3.2/</p>
 */
@Entity
@Table(name = "source")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String provider;

    private String board;

    private String country;

    @Enumerated(EnumType.STRING)
    private Availability availability = Availability.OK;

    private Instant failingSince;

    /**
     * Для JPA.
     */
    protected Source() {
    }

    /**
     * @param provider код провайдера, например {@code greenhouse}
     * @param board    идентификатор доски у провайдера
     */
    public Source(String provider, String board) {
        this.provider = provider;
        this.board = board;
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getBoard() {
        return board;
    }

    public String getCountry() {
        return country;
    }

    public Availability getAvailability() {
        return availability;
    }

    /**
     * Чтение получило ответ списка (полное или неполное): источник доступен, серия отказов
     * прервана.
     */
    public void markRead() {
        this.availability = Availability.OK;
        this.failingSince = null;
    }

    /**
     * Источник не отдал список. Постоянный отказ — сразу «недоступен»; временный — «временные
     * отказы» с начала серии, «недоступен» — когда серия длится не меньше {@code unavailableAfter}.
     *
     * @param temporary        отказ временный (таймаут, 5xx, 429)
     * @param now              время отказа
     * @param unavailableAfter срок серии временных отказов до «недоступен»
     */
    public void markFailure(boolean temporary, Instant now, Duration unavailableAfter) {
        if (failingSince == null) {
            this.failingSince = now;
        }
        boolean expired = !Duration.between(failingSince, now).minus(unavailableAfter).isNegative();
        this.availability = !temporary || expired ? Availability.UNAVAILABLE : Availability.TEMP_FAILING;
    }
}
