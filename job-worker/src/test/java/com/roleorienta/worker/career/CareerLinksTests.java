package com.roleorienta.worker.career;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/**
 * Ссылки на доски систем найма, кадровая страница, страницы вакансий, разметка {@code JobPosting}.
 */
class CareerLinksTests {

    /**
     * Workday с языком в пути, Greenhouse (обе витрины и встраивание), Personio; прочие ссылки — нет.
     */
    @Test
    void findsBoardsOfSupportedProviders() {
        Document page = Jsoup.parse("""
                <a href="https://acme.wd3.myworkdayjobs.com/en-US/External/job/Bratislava/Dev_1">Dev</a>
                <a href="https://boards.greenhouse.io/beta">Beta</a>
                <a href="https://job-boards.greenhouse.io/gamma/jobs/1">Gamma</a>
                <script src="https://boards.greenhouse.io/embed/job_board/js?for=delta"></script>
                <a href="https://epsilon.jobs.personio.de/job/5">Epsilon</a>
                <a href="https://www.linkedin.com/company/acme/jobs">LinkedIn</a>
                """, "https://acme.sk/");

        assertThat(CareerLinks.boards(page)).containsExactly(
                new Board("workday", "acme.wd3.myworkdayjobs.com/external"),
                new Board("greenhouse", "beta"), new Board("greenhouse", "gamma"), new Board("greenhouse", "delta"),
                new Board("personio", "epsilon.jobs.personio.de"));
    }

    /**
     * Кадровая страница — по тексту ссылки или адресу, только того же сайта ({@code www.} не мешает).
     */
    @Test
    void findsCareerPageOfSameSite() {
        Document page = Jsoup.parse("""
                <a href="https://other.sk/kariera">Iná firma</a>
                <a href="/o-nas">O nás</a>
                <a href="https://www.acme.sk/pridaj-sa-k-nam">Pridaj sa k nám</a>
                """, "https://acme.sk/");

        assertThat(CareerLinks.careerPage(page, "acme.sk")).contains("https://www.acme.sk/pridaj-sa-k-nam");
        assertThat(CareerLinks.careerPage(Jsoup.parse("<a href='/o-nas'>O nás</a>", "https://acme.sk/"), "acme.sk"))
                .isEmpty();
    }

    /**
     * Страницы вакансий — ссылки вглубь кадровой; разметка {@code JobPosting} — в JSON-LD.
     */
    @Test
    void findsVacancyLinksAndJobPostingMarkup() {
        Document career = Jsoup.parse("""
                <a href="/kariera/">Kariéra</a><a href="/kariera/java">Java</a><a href="/kontakt">Kontakt</a>
                <a href="/kariera/qa">QA</a>
                """, "https://acme.sk/kariera");

        assertThat(CareerLinks.vacancyLinks(career, URI.create("https://acme.sk/kariera"), 1))
                .containsExactly("https://acme.sk/kariera/java");
        assertThat(CareerLinks.hasJobPosting(Jsoup.parse(
                "<script type=\"application/ld+json\">{\"@type\": \"JobPosting\"}</script>"))).isTrue();
        assertThat(CareerLinks.hasJobPosting(career)).isFalse();
    }

    /**
     * Адреса Workday, которые не доски: {@code robots.txt}, код языка без сайта, служебный путь;
     * язык перед сайтом пропускается. Записанный раньше мусор распознаётся.
     */
    @Test
    void skipsWorkdayAddressesThatAreNotBoards() {
        assertThat(CareerLinks.board("https://acme.wd3.myworkdayjobs.com/robots.txt")).isEmpty();
        assertThat(CareerLinks.board("https://acme.wd3.myworkdayjobs.com/es")).isEmpty();
        assertThat(CareerLinks.board("https://acme.wd3.myworkdayjobs.com/wday/cxs/acme/External/jobs")).isEmpty();
        assertThat(CareerLinks.board("https://acme.wd103.myworkdayjobs.com/es/AccentureCareers/job/1"))
                .contains(new Board("workday", "acme.wd103.myworkdayjobs.com/accenturecareers"));

        assertThat(CareerLinks.isBoard(new Board("workday", "acme.wd3.myworkdayjobs.com/robots"))).isFalse();
        assertThat(CareerLinks.isBoard(new Board("workday", "acme.wd3.myworkdayjobs.com/es"))).isFalse();
        assertThat(CareerLinks.isBoard(new Board("workday", "acme.wd3.myworkdayjobs.com/external"))).isTrue();
        assertThat(CareerLinks.isBoard(new Board("greenhouse", "beta"))).isTrue();
    }

    /**
     * Регистр букв в адресе доски не создаёт второй доски: ключ — в нижнем регистре у всех провайдеров.
     */
    @Test
    void boardKeyIgnoresLetterCase() {
        assertThat(CareerLinks.board("https://ABB.wd3.myworkdayjobs.com/External_Career_Page"))
                .isEqualTo(CareerLinks.board("https://abb.wd3.myworkdayjobs.com/external_career_page"))
                .contains(new Board("workday", "abb.wd3.myworkdayjobs.com/external_career_page"));
        assertThat(CareerLinks.board("https://acme.wd3.myworkdayjobs.com/de-DE/Careers"))
                .contains(new Board("workday", "acme.wd3.myworkdayjobs.com/careers"));
        assertThat(CareerLinks.board("https://boards.greenhouse.io/GitLab"))
                .contains(new Board("greenhouse", "gitlab"));
        assertThat(CareerLinks.board("https://Acme.jobs.personio.de/job/1"))
                .contains(new Board("personio", "acme.jobs.personio.de"));
    }

    /**
     * Кадровые слова и ссылки — как в скрипте замера (§76): «Spolupráca» — не кадровое слово; «Práca u nás» — кадровое
     * (в тексте страницы); ссылка с кадровым словом на систему найма и на хост с брендом сайта принимается, на площадку
     * вакансий ({@code teamio}) — нет; ссылка того же регистрируемого домена ({@code www.acme.com} с
     * {@code sk.acme.com}) — своя.
     */
    @Test
    void findsCareerPageLikeSurveyScript() {
        Document cooperation = Jsoup.parse("<a href=\"/spolupraca\">Spolupráca</a>", "https://acme.sk/");
        assertThat(CareerLinks.careerPage(cooperation, "acme.sk")).isEmpty();
        assertThat(CareerLinks.isCareerPage(Jsoup.parse("<h1>Spolupráca s partnermi</h1>"))).isFalse();
        assertThat(CareerLinks.isCareerPage(Jsoup.parse("<p>Práca u nás</p>"))).isTrue();

        Document recruiting = Jsoup.parse("""
                <a href="https://www.teamio.com/acme">Kariéra</a><a href="https://acme.teamtailor.com/">Kariéra</a>
                """, "https://acme.sk/");
        assertThat(CareerLinks.careerPage(recruiting, "acme.sk")).contains("https://acme.teamtailor.com/");
        Document group = Jsoup.parse("<a href=\"https://www.acme-group.com/careers\">Careers</a>", "https://acme.sk/");
        assertThat(CareerLinks.careerPage(group, "acme.sk")).contains("https://www.acme-group.com/careers");
        Document sameDomain = Jsoup.parse("<a href=\"https://www.acme.com/careers\">Careers</a>",
                "https://sk.acme.com/");
        assertThat(CareerLinks.careerPage(sameDomain, "sk.acme.com")).contains("https://www.acme.com/careers");
    }

    /**
     * Аудит §78: «Spolupráca v regióne» — не кадровое слово («práca v» — только с начала слова); хост с брендом сайта в
     * имени — кадровый сайт группы ({@code skupinazse.sk} у {@code zse.sk}, как в скрипте); ссылка на саму страницу («Kariéra» с {@code href="#"})
     * — не кадровая, пока в якоре или хосте сайта нет кадрового слова ({@code kariera.acme.sk/}, {@code /#kariera}).
     */
    @Test
    void rejectsCooperationOtherBrandAndSelfLink() {
        Document cooperation = Jsoup.parse("<a href=\"/region\">Spolupráca v regióne</a>", "https://acme.sk/");
        assertThat(CareerLinks.careerPage(cooperation, "acme.sk")).isEmpty();
        assertThat(CareerLinks.isCareerPage(Jsoup.parse("<p>Spolupráca v regióne</p>"))).isFalse();
        assertThat(CareerLinks.isCareerPage(Jsoup.parse("<p>Práca v našom tíme</p>"))).isTrue();

        Document groupBrand = Jsoup.parse("<a href=\"https://www.skupinazse.sk/Kariera\">Kariéra</a>", "https://www.zse.sk/");
        assertThat(CareerLinks.careerPage(groupBrand, "www.zse.sk")).contains("https://www.skupinazse.sk/Kariera");
        Document hyphenBrand = Jsoup.parse("<a href=\"https://www.skoda-auto-group.com/careers\">Careers</a>",
                "https://www.skoda-auto.sk/");
        assertThat(CareerLinks.careerPage(hyphenBrand, "www.skoda-auto.sk"))
                .contains("https://www.skoda-auto-group.com/careers");

        Document menu = Jsoup.parse("<a href=\"#\">Kariéra</a><a href=\"/kariera\">Kariéra</a>", "https://acme.sk/");
        assertThat(CareerLinks.careerPage(menu, "acme.sk")).contains("https://acme.sk/kariera");
        Document section = Jsoup.parse("<a href=\"/#kariera\">Pozície</a>", "https://acme.sk/");
        assertThat(CareerLinks.careerPage(section, "acme.sk")).contains("https://acme.sk/");
        Document careerSite = Jsoup.parse("<a href=\"/\">Kariéra</a>", "https://kariera.acme.sk/");
        assertThat(CareerLinks.careerPage(careerSite, "kariera.acme.sk")).contains("https://kariera.acme.sk/");
    }

    /**
     * §79: адрес ссылки с «é» — в кодировке для запроса (RWA: {@code /kariéra+2500++1003045}); сайт сам кадровый — по
     * кадровому слову в имени хоста.
     */
    @Test
    void encodesLinkAndRecognizesCareerSite() {
        Document rwa = Jsoup.parse("<a href=\"/kariéra+2500++1003045\">Kariéra</a>", "https://www.rwa.sk/");
        assertThat(CareerLinks.careerPage(rwa, "www.rwa.sk")).contains("https://www.rwa.sk/kari%C3%A9ra+2500++1003045");
        assertThat(CareerLinks.careerSite("kariera.sconto.sk")).isTrue();
        assertThat(CareerLinks.careerSite("www.dm-jobs.sk")).isTrue();
        assertThat(CareerLinks.careerSite("www.sconto.sk")).isFalse();
    }

    /**
     * SmartRecruiters: кадровая страница компании и вакансия — доска компании в нижнем регистре;
     * служебные пути — не доски.
     */
    @Test
    void findsSmartRecruitersBoards() {
        assertThat(CareerLinks.board("https://careers.smartrecruiters.com/DeutscheTelekomITSolutionsSlovakia"))
                .contains(new Board("smartrecruiters", "deutschetelekomitsolutionsslovakia"));
        assertThat(CareerLinks.board("https://jobs.smartrecruiters.com/Devoteam/744000096966508-architect"))
                .contains(new Board("smartrecruiters", "devoteam"));
        assertThat(CareerLinks.board("https://jobs.smartrecruiters.com/robots.txt")).isEmpty();
        assertThat(CareerLinks.board("https://jobs.smartrecruiters.com/oneclick-ui/company/1")).isEmpty();
    }

    /**
     * Кадровая страница на поддомене сайта; чужой хост с тем же окончанием — не поддомен; шаг вглубь —
     * другая кадровая ссылка, не сама страница; страница — кадровая, только если в её тексте кадровое слово.
     */
    @Test
    void findsCareerSubdomainDeeperPageAndStandardPage() {
        Document home = Jsoup.parse("""
                <a href="https://notacme.sk/kariera">Iná</a><a href="https://kariera.acme.sk/">Kariéra</a>
                """, "https://www.acme.sk/");
        assertThat(CareerLinks.careerPage(home, "www.acme.sk")).contains("https://kariera.acme.sk/");

        Document career = Jsoup.parse("""
                <a href="/kariera/">Kariéra</a><a href="/kariera/volne-pozicie">Voľné pozície</a>
                """, "https://acme.sk/kariera");
        assertThat(CareerLinks.deeperCareerPage(career, URI.create("https://acme.sk/kariera")))
                .contains("https://acme.sk/kariera/volne-pozicie");

        assertThat(CareerLinks.isCareerPage(Jsoup.parse("<title>Kariéra | Acme</title>"))).isTrue();
        assertThat(CareerLinks.isCareerPage(Jsoup.parse("<title>Acme</title><h1>Vitajte</h1>"))).isFalse();
    }

    /**
     * Сайт SuccessFactors узнаётся по содержимому (образец {@code docs/samples/sf-zf.html}) — доска его хост; другой
     * сайт — нет. Кадровая ссылка на кадровый хост другого домена ({@code jobs.kaufland.com} с {@code kaufland.sk})
     * принимается, на площадку из двух частей ({@code jobs.cz}) и на кадровый хост с другим именем домена
     * ({@code jobs.sap.com}) — нет; своя кадровая ссылка — раньше кадрового хоста другого домена (аудит §66).
     *
     * @throws java.io.IOException образец не прочитан
     */
    @Test
    void findsSuccessFactorsSiteAndCareerHostOfOtherDomain() throws java.io.IOException {
        Document zf = Jsoup.parse(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/samples/sf-zf.html")),
                "https://jobs.zf.com/search/?q=&locationsearch=Slovakia");
        assertThat(CareerLinks.boards(zf)).contains(new Board("successfactors", "jobs.zf.com"));
        assertThat(CareerLinks.successFactorsSite(Jsoup.parse("<p>Acme</p>", "https://www.acme.sk/"))).isEmpty();

        Document home = Jsoup.parse("""
                <a href="https://www.jobs.cz/">Práca</a><a href="https://jobs.kaufland.com/">Ponuky</a>
                """, "https://www.kaufland.sk/");
        assertThat(CareerLinks.careerPage(home, "www.kaufland.sk")).contains("https://jobs.kaufland.com/");
        Document portal = Jsoup.parse("<a href=\"https://jobs.cz/\">Jobs</a>", "https://www.acme.sk/");
        assertThat(CareerLinks.careerPage(portal, "www.acme.sk")).isEmpty();
        Document partner = Jsoup.parse("""
                <a href="https://jobs.sap.com/">Partner jobs</a><a href="https://careers.acme.com/">Careers</a>
                <a href="/kariera">Kariéra</a>
                """, "https://www.acme.sk/");
        assertThat(CareerLinks.careerPage(partner, "www.acme.sk")).contains("https://www.acme.sk/kariera");
        Document onlyPartner = Jsoup.parse("<a href=\"https://jobs.sap.com/\">Jobs</a>", "https://www.acme.sk/");
        assertThat(CareerLinks.careerPage(onlyPartner, "www.acme.sk")).isEmpty();
    }

    /**
     * Nalgoo: ссылки на {@code <организация>.nalgoo-jobs.com} и «ворота» {@code ats.nalgoo.com/<язык>/gate/<организация>}
     * — доски; свой домен ({@code kariera.foxconn.sk}, образец {@code docs/samples/nalgoo-foxconn.html}) узнаётся по
     * данным страницы; ключ {@code hiringOrganization} разметки JSON-LD — не организация Nalgoo (аудит §65).
     *
     * @throws java.io.IOException образец не прочитан
     */
    @Test
    void findsNalgooBoards() throws java.io.IOException {
        assertThat(CareerLinks.board("https://billa.nalgoo-jobs.com/jobs/83976")).contains(new Board("nalgoo", "billa"));
        assertThat(CareerLinks.board("https://ats.nalgoo.com/sk/gate/billa/positions")).contains(new Board("nalgoo", "billa"));
        assertThat(CareerLinks.board("https://www.nalgoo-jobs.com/")).isEmpty();
        Document foxconn = Jsoup.parse(java.nio.file.Files.readString(
                java.nio.file.Path.of("../docs/samples/nalgoo-foxconn.html")), "https://kariera.foxconn.sk/");
        assertThat(CareerLinks.boards(foxconn)).contains(new Board("nalgoo", "foxconn"));
        Document jobPosting = Jsoup.parse("<script type=\"application/ld+json\">{\"hiringOrganization\":\"ACME s.r.o.\"}"
                + "</script><script>{\"organization\":\"foxconn\",\"api\":\"https://ats.nalgoo.com/api\"}</script>");
        assertThat(CareerLinks.nalgooSite(jobPosting)).contains(new Board("nalgoo", "foxconn"));
    }

    /**
     * Phenom узнаётся по содержимому: ресурсы {@code cdn.phenompeople.com} и {@code baseUrl} сайта — доска хост и путь
     * языка (образцы {@code docs/samples/phenom-dhl.html}, {@code phenom-allianz.html}); другой сайт — нет.
     *
     * @throws java.io.IOException образец не прочитан
     */
    @Test
    void findsPhenomSite() throws java.io.IOException {
        Document dhl = Jsoup.parse(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/samples/phenom-dhl.html")),
                "https://careers.dhl.com/eu/sk/home");
        assertThat(CareerLinks.boards(dhl)).contains(new Board("phenom", "careers.dhl.com/eu/sk"));
        Document allianz = Jsoup.parse(java.nio.file.Files.readString(
                java.nio.file.Path.of("../docs/samples/phenom-allianz.html")), "https://careers.allianz.com/global/en");
        assertThat(CareerLinks.phenomSite(allianz)).contains(new Board("phenom", "careers.allianz.com/global/en"));
        assertThat(CareerLinks.phenomSite(Jsoup.parse("<p>\"baseUrl\":\"https://acme.sk/\"</p>"))).isEmpty();
    }

    /**
     * Taleo: адрес раздела в данных скрипта кадровой страницы Slovnaft (образец {@code docs/samples/oracle-slovnaft.html},
     * {@code https:\/\/molgroup.taleo.net\/careersection\/external\/…}) — доска {@code molgroup.taleo.net/external};
     * служебный путь {@code rest} и вход {@code iam} — нет (аудит §65).
     *
     * @throws java.io.IOException образец не прочитан
     */
    @Test
    void findsTaleoBoards() throws java.io.IOException {
        Document slovnaft = Jsoup.parse(java.nio.file.Files.readString(
                java.nio.file.Path.of("../docs/samples/oracle-slovnaft.html")), "https://kariera.slovnaft.sk/");
        assertThat(CareerLinks.boards(slovnaft)).contains(new Board("taleo", "molgroup.taleo.net/external"));
        Document rest = Jsoup.parse("<a href=\"https://acme.taleo.net/careersection/rest/jobboard/x\">x</a>"
                + "<a href=\"https://acme.taleo.net/careersection/iam/accessmanagement/login.jsf?lang=en\">Login</a>");
        assertThat(CareerLinks.taleoBoards(rest)).isEmpty();
    }
}
