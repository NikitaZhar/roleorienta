package com.roleorienta.worker.discovery.cc;

/**
 * Доска, найденная во входе обнаружения (§91): общий вид для всех систем найма.
 *
 * @param slug     slug источника в написании, увиденном первым ({@code tenant/site} у Workday,
 *                 аккаунт у Personio) — станет {@code source.external_ref}
 * @param dedupKey ключ дедупа внутри провайдера (без учёта регистра)
 * @param baseUrl  базовый адрес ленты
 */
public record HarvestedBoard(String slug, String dedupKey, String baseUrl) {
}
