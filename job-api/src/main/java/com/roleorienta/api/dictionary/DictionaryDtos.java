package com.roleorienta.api.dictionary;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Запросы и ответы редактирования словаря позиций администратором (технический документ §8; стенограмма §160).
 * Слова записываются как в словаре: регистр и диакритику при сопоставлении убирает воркер.
 */
public final class DictionaryDtos {

    /** Наибольшая длина слова словаря ({@code position_term.term}). */
    static final int MAX_TERM = 100;
    /** Наибольшее число раз, сколько признак должен встретиться в тексте. */
    static final int MAX_CONTENT_HITS = 100;

    private DictionaryDtos() {
    }

    /**
     * Словарь целиком.
     *
     * @param version              номер версии: растёт при каждом изменении
     * @param contentHits          сколько раз признак специализации должен встретиться в тексте при роли в названии
     * @param otherSpecializations признаки специализаций вне словаря
     * @param positions            позиции по названию
     */
    public record DictionaryView(int version, int contentHits, List<String> otherSpecializations,
            List<PositionView> positions) {
    }

    /**
     * Позиция словаря.
     *
     * @param id        позиция
     * @param code      код (ссылка из условий поиска; не меняется)
     * @param name      название
     * @param placement группа ISCO-08 и включена ли
     * @param terms     названия по видам
     */
    public record PositionView(long id, String code, String name, Placement placement, Terms terms) {
    }

    /**
     * Место позиции среди позиций пользователя.
     *
     * @param group  группа ISCO-08 (4 цифры, §204): позиция видна внутри группы, её вакансии входят в группу; {@code null}
     *               — не задана (позиция добавлена до групп)
     * @param active включена: выключенная не сопоставляется и не предлагается при выборе позиций
     */
    public record Placement(String group, boolean active) {
    }

    /**
     * Названия позиции по видам.
     *
     * @param titles   эквивалентные названия (хотя бы одно)
     * @param roles    роли в названии (developer, engineer …)
     * @param keywords признаки специализации — в названии вместе с ролью или в тексте
     * @param exclude  слова названия, исключающие позицию
     */
    public record Terms(@NotEmpty List<@NotBlank @Size(max = MAX_TERM) String> titles,
            @NotNull List<@NotBlank @Size(max = MAX_TERM) String> roles,
            @NotNull List<@NotBlank @Size(max = MAX_TERM) String> keywords,
            @NotNull List<@NotBlank @Size(max = MAX_TERM) String> exclude) {
    }

    /**
     * Новая позиция.
     *
     * @param code  код: латиница в нижнем регистре, цифры, дефис между ними ({@code java-developer}); не одни цифры —
     *              4 цифры в условиях означают группу ISCO-08 (аудит §211)
     * @param name  название
     * @param group группа ISCO-08 (4 цифры справочника)
     * @param terms названия по видам
     */
    public record NewPosition(@NotBlank @Size(max = 60) @Pattern(regexp = "(?![0-9]+$)[a-z0-9]+(-[a-z0-9]+)*") String code,
            @NotBlank @Size(max = 200) String name, @NotNull @Pattern(regexp = "[0-9]{4}") String group, @NotNull @Valid Terms terms) {
    }

    /**
     * Изменение позиции (код не меняется).
     *
     * @param name   название
     * @param active включена — обязательно: без поля позиция не выключается молча (аудит §165)
     * @param group  группа ISCO-08 (4 цифры справочника)
     * @param terms  названия по видам — заменяют прежние
     */
    public record PositionChange(@NotBlank @Size(max = 200) String name, @NotNull Boolean active,
            @NotNull @Pattern(regexp = "[0-9]{4}") String group, @NotNull @Valid Terms terms) {
    }

    /**
     * Общие настройки словаря.
     *
     * @param contentHits          сколько раз признак специализации должен встретиться в тексте при роли в названии
     * @param otherSpecializations признаки специализаций вне словаря
     */
    public record Settings(@Min(1) @Max(MAX_CONTENT_HITS) int contentHits,
            @NotNull List<@NotBlank @Size(max = MAX_TERM) String> otherSpecializations) {
    }

    /**
     * Запрос подсказки позиции, который ничего не нашёл (§205): по нему администратор решает, нужна ли новая позиция
     * словаря.
     *
     * @param query  строка (в нижнем регистре)
     * @param misses сколько раз
     * @param lastAt когда последний раз
     */
    public record SearchMiss(String query, int misses, Instant lastAt) {
    }
}
