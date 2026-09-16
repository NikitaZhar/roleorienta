package com.roleorienta.worker.normalize;

import com.roleorienta.core.domain.WorkModality;

/**
 * Результат нормализации локации (§6): город, страна и формат работы.
 *
 * <p>{@link #ABSENT} — локации нет вовсе (все поля {@code null}). Если локация есть,
 * но формат работы из строки не следует, {@code modality} равен
 * {@link WorkModality#UNKNOWN} — это явное «неизвестно», отличимое от «локации нет».
 * Город и страна заполняются только для обычного места (не remote/hybrid); иначе
 * {@code null}.</p>
 *
 * @param city     город или {@code null}
 * @param country  страна/регион или {@code null}
 * @param modality формат работы или {@code null} (если локации нет)
 */
public record NormalizedLocation(String city, String country, WorkModality modality) {

    /** Локация не указана: все поля {@code null}. */
    public static final NormalizedLocation ABSENT = new NormalizedLocation(null, null, null);
}
