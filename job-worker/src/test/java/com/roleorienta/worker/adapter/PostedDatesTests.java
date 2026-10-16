package com.roleorienta.worker.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Даты размещения из записей источников (§127): дата или момент в разных записях смещения — день UTC.
 */
class PostedDatesTests {

    private static final Instant DAY = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void parsesDateAtStartOfValue() {
        assertThat(PostedDates.parse("2026-08-20")).isEqualTo(DAY);
        assertThat(PostedDates.parse("2026-08-20T17:56:51.721+0000")).isEqualTo(DAY);
        assertThat(PostedDates.parse("2026-08-20T14:28:14+01:00")).isEqualTo(DAY);
        assertThat(PostedDates.parse(null)).isNull();
        assertThat(PostedDates.parse("Posted Today")).isNull();
        assertThat(PostedDates.parse("2026-13-40")).isNull();
    }

    @Test
    void attachesFirstDateOfEachPostingToRead() {
        PostedDates dates = new PostedDates();
        dates.put("a", DAY);
        dates.put("a", Instant.parse("2026-09-01T00:00:00Z"));
        dates.put("b", null);

        SourceReadResult.Read read = (SourceReadResult.Read) dates.attach(SourceReadResult.Read.full(List.of(),
                List.of()));

        assertThat(read.postedAt()).containsExactlyEntriesOf(Map.of("a", DAY));
    }
}
