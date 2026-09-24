package com.roleorienta.api.posting;

/**
 * Параметры страницы ленты (§7, §70): курсор, размер и порядок. Сгруппированы в запись,
 * чтобы методы ленты не превышали лимит «≤5 параметров» (контракт §3.10); Spring MVC
 * связывает одноимённые query-параметры ({@code cursor}, {@code limit}, {@code sort}) с
 * полями записи, как и у {@link PostingFilter}.
 *
 * @param cursor курсор из {@code nextCursor} предыдущей страницы или {@code null} — с начала
 * @param limit  желаемый размер страницы или {@code null} (по умолчанию/максимум задаёт сервис)
 * @param sort   порядок ленты или {@code null} — {@link Sort#ID}
 */
public record FeedPaging(Long cursor, Integer limit, Sort sort) {

    /** Порядок ленты. */
    public enum Sort {
        /** По возрастанию {@code id} (порядок появления в базе) — по умолчанию. */
        ID,
        /** Свежие первыми: по убыванию даты публикации, без даты — в конце. */
        POSTED
    }

    /** @return порядок ленты; {@code null} → {@link Sort#ID} */
    public Sort sortOrDefault() {
        return sort == null ? Sort.ID : sort;
    }
}
