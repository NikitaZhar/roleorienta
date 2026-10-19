package com.roleorienta.worker.adapter.careerpage;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.careerpage.CareerPageList.Item;
import java.net.URI;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/**
 * Разбор собственной кадровой страницы (§136) на страницах, устроенных как в замере 2026-10-19 (finalcd.sk, spa.sk,
 * kariera.ppacontroll.sk, bombayexpress.sk).
 */
class CareerPageListTests {

    private static final String LIST = "https://www.firma.sk/kariera/";

    /**
     * Ссылки списка с общим родительским путём; меню, подвал, PDF и кнопка «Viac» — не вакансии; ссылка-картинка и
     * ссылка-название на одну вакансию — одна вакансия с названием.
     */
    @Test
    void findsVacancyLinksSharingParentPath() {
        Document page = page("""
                <nav><a href="/kariera/">Kariéra</a><a href="/kontakt/">Kontakt</a></nav>
                <h1>Voľné pracovné pozície</h1>
                <ul>
                  <li><a href="/kariera/34-autoelektrikar/"><img src="a.jpg"></a>
                      <a href="/kariera/34-autoelektrikar/">Autoelektrikár, Prievidza</a>
                      <a href="/kariera/34-autoelektrikar/">Viac</a></li>
                  <li><a href="/kariera/26-autoklampiar/">Autoklampiar</a> <span>Bratislava</span></li>
                  <li><a href="/files/podmienky.pdf">Podmienky</a></li>
                </ul>
                <footer><a href="/kariera/gdpr/">Ochrana osobných údajov pre uchádzačov</a></footer>
                """);

        assertThat(CareerPageList.vacancies(page)).containsExactly(
                new Item("https://www.firma.sk/kariera/34-autoelektrikar/", "Autoelektrikár, Prievidza", ""),
                new Item("https://www.firma.sk/kariera/26-autoklampiar/", "Autoklampiar", "Bratislava"));
    }

    /**
     * Карточка вакансии на кадровом поддомене: место — текст карточки без названия; ссылки страниц списка — не
     * вакансии, следующая страница — по «Ďalej».
     */
    @Test
    void readsCardTextAndNextPage() {
        Document page = page("""
                <div class="job"><span>PUBLIKOVANÉ 12.10.2026</span>
                  <a href="https://kariera.firma.sk/profesia/it-specialista/">IT špecialista</a>
                  <span>Vajnorská 140A, Bratislava, Slovensko</span></div>
                <div class="job"><a href="https://kariera.firma.sk/profesia/plc-programator/">PLC programátor</a>
                  <span>Piešťany</span></div>
                <a href="https://kariera.firma.sk/page/2/">2</a>
                <a href="https://kariera.firma.sk/page/2/">Ďalej »</a>
                """);

        assertThat(CareerPageList.vacancies(page)).extracting(Item::card)
                .containsExactly("PUBLIKOVANÉ 12.10.2026 Vajnorská 140A, Bratislava, Slovensko", "Piešťany");
        assertThat(CareerPageList.nextPage(page, Set.of())).contains(URI.create("https://kariera.firma.sk/page/2/"));
        assertThat(CareerPageList.nextPage(page, Set.of("https://kariera.firma.sk/page/2/"))).isEmpty();
    }

    /**
     * Одна ссылка — список, только если в родительском пути кадровое слово; ссылки чужого сайта и корня — нет.
     */
    @Test
    void singleLinkCountsOnlyUnderCareerPath() {
        assertThat(CareerPageList.vacancies(page("<a href=\"/kariera/sefkuchar\">Šéfkuchár</a>")))
                .extracting(Item::title).containsExactly("Šéfkuchár");
        assertThat(CareerPageList.vacancies(page("<a href=\"/o-firme/historia\">História firmy</a>"))).isEmpty();
        assertThat(CareerPageList.vacancies(page("<a href=\"/sefkuchar\">Šéfkuchár</a>"
                + "<a href=\"/casnik\">Čašník</a>"))).isEmpty();
        assertThat(CareerPageList.vacancies(page("<a href=\"https://www.profesia.sk/praca/firma/a\">Čašník</a>"
                + "<a href=\"https://www.profesia.sk/praca/firma/b\">Kuchár</a>"))).isEmpty();
    }

    /**
     * Фраза «вакансий нет» — с диакритикой и без; обычный текст — нет.
     */
    @Test
    void recognisesNoVacanciesPhrase() {
        assertThat(CareerPageList.saysNoVacancies(page("<p>V súčasnosti neponúkame žiadne pracovné pozície.</p>")))
                .isTrue();
        assertThat(CareerPageList.saysNoVacancies(page("<p>Momentálne nie sú voľné pracovné miesta.</p>"))).isTrue();
        assertThat(CareerPageList.saysNoVacancies(page("<p>Currently no open positions.</p>"))).isTrue();
        assertThat(CareerPageList.saysNoVacancies(page("<p>Pošlite nám životopis.</p>"))).isFalse();
    }

    private static Document page(String body) {
        return Jsoup.parse("<html><body>" + body + "</body></html>", LIST);
    }
}
