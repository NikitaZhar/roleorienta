package com.roleorienta.worker.career;

/**
 * Найденный источник: провайдер (формат) и доска у провайдера — как в таблице {@code source}.
 *
 * @param provider код провайдера
 * @param board    доска у провайдера
 */
public record Board(String provider, String board) {
}
