package com.roleorienta.worker.adapters;

/**
 * Сведения о доске, которые сообщает сам провайдер, — для проверки принадлежности доски
 * работодателю в гейте обнаружения (A2, §79).
 *
 * @param owner       имя владельца доски у провайдера (у Workday — тенант, поддомен клиента)
 * @param description описание работодателя со страницы доски; пустая строка — описания нет
 */
public record BoardProfile(String owner, String description) {
}
