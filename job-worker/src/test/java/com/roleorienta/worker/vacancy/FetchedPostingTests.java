package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Длинные название и место публикации обрезаются до поля хранения; короткие и пустые не меняются.
 */
class FetchedPostingTests {

    @Test
    void cutsTitleAndLocationToStoredLength() {
        String longText = "Bratislava; ".repeat(60);

        FetchedPosting posting = new FetchedPosting("id", longText, "https://x", longText, null);

        assertThat(posting.title()).hasSize(FetchedPosting.MAX_TEXT);
        assertThat(posting.location()).hasSize(FetchedPosting.MAX_TEXT);
        assertThat(new FetchedPosting("id", "Nákupca", "https://x", null, null))
                .isEqualTo(new FetchedPosting("id", "Nákupca", "https://x", null, null))
                .extracting(FetchedPosting::title, FetchedPosting::location).containsExactly("Nákupca", null);
    }
}
