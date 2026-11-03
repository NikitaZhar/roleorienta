package com.roleorienta.worker.adapter.careerpage;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.adapter.careerpage.CareerPageList.Item;
import com.roleorienta.worker.browser.LoadMore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/**
 * Чтение настоящих кадровых страниц эталона §247 (§255): HTML, который отдаёт сервер (сняты 2026-11-03 скриптом
 * {@code target/careerpage-samples.sh}, {@code docs/samples/careerpage/}); правда — из браузера и из текста самих страниц
 * («Aktuálne máme dostupných: 191 voľných pozícií» у Penta). Каждый случай — что именно ядро должно прочитать с первой
 * страницы списка: число вакансий, точные названия, следующую страницу.
 */
class CareerPageSamplesTests {

    private static final Path SAMPLES = Path.of("../docs/samples/careerpage");

    /**
     * Penta Hospitals: 191 вакансия (столько объявлено на странице), названия — заголовки карточек, без текста карточки.
     */
    @Test
    void readsPentaHospitalsTitlesFromCardHeadings() throws IOException {
        List<Item> items = CareerPageList.vacancies(page("penta", "https://pentahospitals.sk/volne-pozicie/"));

        assertThat(items).hasSize(191);
        assertThat(items).extracting(Item::title).contains("Sestra na ambulancii-ProCare Bory- Bratislava (m/ž)",
                "Sanitár – Na viaceré oddelenia Nemocnica Topoľčany (m/ž)");
        assertThat(items).extracting(Item::title).noneMatch(title -> title.contains("Nemocnice Penta Hospitals"));
    }

    /**
     * Tatra banka: 15 вакансий первой страницы без номера карточки впереди; следующую страницу листает скрипт — чтение
     * неполное, а не «полное из 15».
     */
    @Test
    void readsTatraBankaFirstPageAndSeesScriptPaging() throws IOException {
        Document page = page("tatra", "https://www.prirodzenenajlepsi.sk/volne-pracovne-miesta/?chcem=pracovat");

        assertThat(CareerPageList.vacancies(page)).hasSize(15).first().extracting(Item::title)
                .isEqualTo("Poradca Raiffeisen banky (M/Ž) pre Komárno");
        assertThat(CareerPageList.nextPage(page, Set.of())).isEmpty();
        assertThat(CareerPageList.scriptNext(page)).isTrue();
    }

    /**
     * AGEL: в карточке название — в {@code <article><header>}; в HTML 7 вакансий, остальные — кнопкой «Zobraziť viac»
     * (раскрывает браузер); главная кадрового сайта — не список (ссылка на список — не вакансия).
     */
    @Test
    void readsAgelCardsInsideArticleHeaders() throws IOException {
        Document list = page("agel-list", "https://kariera.agel.sk/ponuka-prace/");

        assertThat(CareerPageList.vacancies(list)).hasSize(7).extracting(Item::title)
                .contains("Aplikačný administrátor (M/Ž)");
        assertThat(LoadMore.present(list)).isTrue();
        assertThat(CareerPageList.vacancies(page("agel-root", "https://kariera.agel.sk/"))).isEmpty();
    }

    /**
     * Manuvia и Lugera: вакансии в корне кадрового хоста или под {@code /praca/}; страницы — нумерацией без «далее»
     * ({@code ?strana=2}, {@code /page/2/}).
     */
    @Test
    void readsNumberedPagingOfManuviaAndLugera() throws IOException {
        Document manuvia = page("manuvia", "https://ponukyprace.manuvia.com/ponuky/");
        assertThat(CareerPageList.vacancies(manuvia)).hasSize(12).extracting(Item::title)
                .contains("Supervízor výroby (m/ž)");
        assertThat(CareerPageList.nextPage(manuvia, Set.of()))
                .contains(URI.create("https://ponukyprace.manuvia.com/ponuky/?strana=2"));

        Document lugera = page("lugera", "https://lugera.sk/pracovne-ponuky/");
        assertThat(CareerPageList.vacancies(lugera)).hasSize(9).extracting(Item::title)
                .contains("Projektový/á manažér/ka");
        assertThat(CareerPageList.nextPage(lugera, Set.of()))
                .contains(URI.create("https://lugera.sk/pracovne-ponuky/page/2/"));
    }

    /**
     * Dr.Max: 151 вакансия ({@code detail-kariery?id=}); ссылки отбора и «в избранное» на саму страницу — не вакансии;
     * кнопка «Zobraziť detail ponuky» — название из карточки.
     */
    @Test
    void readsDrMaxDetailLinksNotFilters() throws IOException {
        List<Item> items = CareerPageList.vacancies(page("drmax", "https://kariera.drmax.sk/sk/volne-pozicie"));

        assertThat(items).hasSize(151);
        assertThat(items).extracting(Item::title).contains("Farmaceut - OD Kaufland, Šamorín")
                .noneMatch(title -> title.startsWith("Zobraziť"));
        assertThat(items).extracting(Item::url).allMatch(url -> url.contains("/detail-kariery?id="));
    }

    /**
     * BENU и TRIGON — простые списки: 15 и 3 вакансии с точными названиями.
     */
    @Test
    void readsBenuAndTrigon() throws IOException {
        assertThat(CareerPageList.vacancies(page("benu", "https://www.phoenix.sk/kariera"))).hasSize(15)
                .extracting(Item::title).contains("Farmaceut/Farmaceutka (M/Ž) - Martin");
        assertThat(CareerPageList.vacancies(page("trigon", "https://trigon-consulting.sk/pracovne-ponuky/")))
                .hasSize(3).extracting(Item::title).contains("Hlavný majster výroby M/Ž");
    }

    /**
     * Не списки вакансий: события и «как мы работаем» на кадровом сайте Kaufland и Slovak Telekom (вакансии там строит
     * скрипт), страницы без списка (SOZA, STAVTECH); RAVEN без скриптов — пустая оболочка; XENEX — вакансии в PDF,
     * TRANSFER — список из JSON без ссылок (не читаются, но и мусора не дают).
     */
    @Test
    void findsNothingOnPagesWithoutHtmlList() throws IOException {
        assertThat(CareerPageList.vacancies(page("kaufland", "https://kariera.kaufland.sk/volne-pracovne-pozicie"))).isEmpty();
        assertThat(CareerPageList.vacancies(page("telekom", "https://www.t-kariera.sk/volne-pozicie"))).isEmpty();
        assertThat(CareerPageList.vacancies(page("soza", "https://www.soza.sk/kariera-v-soza"))).isEmpty();
        assertThat(CareerPageList.vacancies(page("stavtech", "https://stavtech.sk/kariera/"))).isEmpty();
        assertThat(CareerPageList.vacancies(page("raven", "https://www.raven.sk/clanek/214/kariera"))).isEmpty();
        assertThat(CareerPageList.vacancies(page("xenex", "https://xenex.sk/sk/kariera/"))).isEmpty();
        assertThat(CareerPageList.vacancies(page("transfer", "https://transferpersonal.sk/pracovne-ponuky"))).isEmpty();
    }

    private static Document page(String name, String url) throws IOException {
        return Jsoup.parse(Files.readString(SAMPLES.resolve(name + ".html")), url);
    }
}
