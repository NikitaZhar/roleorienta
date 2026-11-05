package com.roleorienta.worker.adapter.stateportal;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * Позиции profesia.sk со страницы объявления (§267) — по разметке настоящих страниц (H&M O5383860, Accenture
 * O5203672, 2026-11-05): ссылки {@code /praca/<ключ>/} после «Pozícia:» до следующей подписи («Spoločnosť:» — ссылка
 * на компанию не позиция); адрес закодирован ({@code &#x2F;}); строки нет — пусто.
 */
class ProfesiaPositionTests {

    private static final String PAGE = """
            <div class="padding-on-bottom overall-info"><span class='hidden-xs'><strong>lokalita: </strong>
            <a href="&#x2F;praca&#x2F;bratislava&#x2F;">Bratislava</a> &nbsp; <strong>Pozícia: </strong>
            <a href="&#x2F;praca&#x2F;dokladac-dokladacka-tovaru-merchandiser&#x2F;">Dokladač/dokladačka tovaru,
            merchandiser</a>, <a href="&#x2F;praca&#x2F;pokladnik-pokladnicka&#x2F;">Pokladník/pokladníčka</a>,
            <a href="&#x2F;praca&#x2F;predavac-predavacka&#x2F;">Predavač/predavačka</a> &nbsp;
            <strong>Spoločnosť: </strong><a href="&#x2F;praca&#x2F;h-m-hennes-mauritz-sk-s-r-o&#x2F;C102499">H &amp; M</a>
            </span></div>""";

    @Test
    void readsPositionsUntilNextLabel() {
        assertThat(ProfesiaPosition.of(Jsoup.parse(PAGE, "https://www.profesia.sk/O5383860"))).containsExactly(
                new ProfesiaPosition("dokladac-dokladacka-tovaru-merchandiser", "Dokladač/dokladačka tovaru, merchandiser"),
                new ProfesiaPosition("pokladnik-pokladnicka", "Pokladník/pokladníčka"),
                new ProfesiaPosition("predavac-predavacka", "Predavač/predavačka"));
    }

    @Test
    void englishOfferUsesSameLabel() {
        String page = "<span><strong>Pozícia: </strong><a href=\"/praca/database-administrator/\">Database "
                + "Administrator</a>, <a href=\"/praca/it-consultant/\">IT Consultant</a></span>";

        assertThat(ProfesiaPosition.of(Jsoup.parse(page))).extracting(ProfesiaPosition::slug)
                .containsExactly("database-administrator", "it-consultant");
    }

    @Test
    void pageWithoutPositionsGivesNone() {
        assertThat(ProfesiaPosition.of(Jsoup.parse("<strong>lokalita: </strong><a href=\"/praca/nitra/\">Nitra</a>")))
                .isEmpty();
    }
}
