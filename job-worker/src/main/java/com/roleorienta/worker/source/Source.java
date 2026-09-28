package com.roleorienta.worker.source;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Источник вакансий — подключённая кадровая страница: провайдер (формат) и доска у провайдера.
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
}
