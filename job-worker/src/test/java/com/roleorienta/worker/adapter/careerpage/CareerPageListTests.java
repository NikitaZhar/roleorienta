package com.roleorienta.worker.adapter.careerpage;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.careerpage.CareerPageList.Item;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
     * Группа без кадрового слова в пути — не список, даже самая большая (§183: товары, разделы сайта): берётся меньшая
     * группа под кадровым путём («karriere» — тоже кадровый); кадровой нет — списка нет.
     */
    @Test
    void largestGroupMustBeUnderCareerPath() {
        String products = "<a href=\"/produkty/kombajny\">Kombajny</a>"
                + "<a href=\"/produkty/postrekovace\">Postrekovače</a><a href=\"/produkty/traktory\">Traktory</a>";

        assertThat(CareerPageList.vacancies(page(products + "<a href=\"/kariera/mechanik\">Mechanik</a>")))
                .extracting(Item::title).containsExactly("Mechanik");
        assertThat(CareerPageList.vacancies(page(products + "<a href=\"/karriere/mechaniker\">Mechaniker</a>")))
                .extracting(Item::title).containsExactly("Mechaniker");
        assertThat(CareerPageList.vacancies(page(products))).isEmpty();
    }

    /**
     * Кадровое слово в хосте ссылок — тоже кадровый путь (§186: careers.priva.com — вакансии под {@code /o/}).
     */
    @Test
    void careerHostCountsAsCareerPath() {
        assertThat(CareerPageList.vacancies(page("<a href=\"https://careers.firma.sk/o/mechanik\">Mechanik</a>"
                + "<a href=\"https://careers.firma.sk/o/skladnik\">Skladník</a>")))
                .extracting(Item::title).containsExactly("Mechanik", "Skladník");
    }

    /**
     * Список подтверждается текстом (§183, §186): из первых трёх открывшихся страниц хотя бы одна — с признаком
     * вакансии в основном тексте; признак только в меню или подвале — не в счёт; страницы о товаре или новости — нет;
     * ни одна не открылась или список пуст — не подтверждён.
     */
    @Test
    void listIsConfirmedByVacancyText() {
        Map<String, String> texts = Map.of(
                LIST + "a", "<h1>Mechanik</h1><p>Požiadavky: vodičský preukaz. Ponúkame stabilnú prácu.</p>",
                LIST + "b", "<h1>Skladník</h1><p>We offer flexible hours. Requirements: forklift licence.</p>",
                LIST + "c", "<h1>Kombajn X9</h1><p>Výkon 500 kW, zásobník 14 000 l.</p>",
                LIST + "d", "<h1>Novinky</h1><p>Otvorili sme novú prevádzku.</p>",
                LIST + "e", "<nav>Kariéra: pošlite životopis</nav><h1>Blog</h1><footer>We offer jobs</footer>",
                LIST + "f", "<nav>Ponúkame</nav><main><h1>Blog</h1><p>Nový článok.</p></main>");

        assertThat(CareerPageList.confirmed(List.of(LIST + "a", LIST + "c"), open(texts))).isTrue();
        assertThat(CareerPageList.confirmed(List.of(LIST + "c", LIST + "d", LIST + "a"), open(texts))).isTrue();
        assertThat(CareerPageList.confirmed(List.of(LIST + "c", LIST + "d"), open(texts))).isFalse();
        assertThat(CareerPageList.confirmed(List.of(LIST + "e", LIST + "f"), open(texts))).isFalse();
        assertThat(CareerPageList.confirmed(List.of(LIST + "x", LIST + "y"), open(texts))).isFalse();
        assertThat(CareerPageList.confirmed(List.of(), open(texts))).isFalse();
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

    /** Страница по адресу из {@code texts}; нет адреса — не открылась. */
    private static Function<URI, Optional<Document>> open(Map<String, String> texts) {
        return uri -> Optional.ofNullable(texts.get(uri.toString())).map(body -> Jsoup.parse(body, uri.toString()));
    }

    private static Document page(String body) {
        return Jsoup.parse("<html><body>" + body + "</body></html>", LIST);
    }
}
