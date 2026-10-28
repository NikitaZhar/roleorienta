package com.roleorienta.worker.stateportal;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.stateportal.ProfesiaOfferPage.Offer;
import java.time.Instant;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * Разбор страницы объявления profesia.sk (§219) по разметке настоящей страницы (объявление Brightpick, 2026-10-27,
 * сокращено): название, работодатель из заголовка, место, дата, текст без раздела о компании.
 */
class ProfesiaOfferPageTests {

    /** Сокращённая страница объявления: разметка как у profesia.sk. */
    static final String PAGE = """
            <html><head><title>DevOps Manager - Brightpick s. r. o. | PROFESIA.SK</title></head><body>
            <header><p>Naše sídlo je v Bratislave.</p></header>
            <section><h1>DevOps Manager</h1>
            <div class="panel-body"><span itemprop="jobLocation" itemscope itemtype="http://schema.org/Place">
              <span itemprop="address">Plynárenská 4937/6, Ružinov, Slovensko, Bratislava</span></span>
              <span itemprop="employmentType">plný úväzok</span></div>
            <span class="hidden" itemprop="datePosted">2026-10-25</span>
            <div class='job-info details-section'><h3>Informácie o pracovnom mieste</h3>
              <div class='details-desc'><p>Spravuješ Kubernetes a CI/CD.</p></div></div>
            <div class='job-requirements details-section'><h3>Požiadavky na zamestnanca</h3>
              <h4>Jazykové znalosti</h4><p>Angličtina - B2</p></div>
            <div class='company-info details-section'><h3>Inzerujúci</h3><p>Robotika pre sklady.</p></div>
            </section></body></html>
            """;

    /** Все сведения на месте; раздел о компании в текст не попадает. */
    @Test
    void readsOfferFacts() {
        Offer offer = ProfesiaOfferPage.parse(Jsoup.parse(PAGE)).orElseThrow();

        assertThat(offer.title()).isEqualTo("DevOps Manager");
        assertThat(offer.employerName()).isEqualTo("Brightpick s. r. o.");
        assertThat(offer.location()).isEqualTo("Plynárenská 4937/6, Ružinov, Slovensko, Bratislava");
        assertThat(offer.postedAt()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
        assertThat(offer.content()).contains("Kubernetes", "Angličtina - B2").doesNotContain("Robotika");
    }

    /** Страница без {@code <h1>} — не объявление; чего нет — {@code null}; заголовок другого вида — без работодателя. */
    @Test
    void missingPartsAreNull() {
        assertThat(ProfesiaOfferPage.parse(Jsoup.parse("<html><body><p>Ponuka nie je dostupná</p></body></html>")))
                .isEmpty();
        Offer bare = ProfesiaOfferPage.parse(Jsoup.parse("<html><head><title>Profesia</title></head>"
                + "<body><h1>Skladník</h1></body></html>")).orElseThrow();
        assertThat(bare.employerName()).isNull();
        assertThat(bare.location()).isNull();
        assertThat(bare.content()).isNull();
        assertThat(bare.postedAt()).isNull();
        assertThat(ProfesiaOfferPage.employer("Skladník - Tesco Stores SR, a.s. | PROFESIA.SK", "Skladník"))
                .isEqualTo("Tesco Stores SR, a.s.");
    }
}
