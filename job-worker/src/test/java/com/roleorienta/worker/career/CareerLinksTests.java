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
                new Board("workday", "acme.wd3.myworkdayjobs.com/External"),
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
                .contains(new Board("workday", "acme.wd103.myworkdayjobs.com/AccentureCareers"));

        assertThat(CareerLinks.isBoard(new Board("workday", "acme.wd3.myworkdayjobs.com/robots"))).isFalse();
        assertThat(CareerLinks.isBoard(new Board("workday", "acme.wd3.myworkdayjobs.com/es"))).isFalse();
        assertThat(CareerLinks.isBoard(new Board("workday", "acme.wd3.myworkdayjobs.com/External"))).isTrue();
        assertThat(CareerLinks.isBoard(new Board("greenhouse", "beta"))).isTrue();
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
}
