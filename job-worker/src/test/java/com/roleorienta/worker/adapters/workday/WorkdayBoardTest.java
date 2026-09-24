package com.roleorienta.worker.adapters.workday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkdayBoard#fromCareerUrl} — извлечение {@code tenant/site} из URL страницы
 * карьеры (вход Common Crawl, §54). Примеры — формы URL из живого среза индекса
 * {@code CC-MAIN-2026-39} (2026-09-23).
 */
class WorkdayBoardTest {

    @Test
    void localeSiteAndJobPath() {
        assertEquals(
                Optional.of(new WorkdayBoard("amgen", "Careers", "https://amgen.wd1.myworkdayjobs.com")),
                WorkdayBoard.fromCareerUrl(
                        "https://amgen.wd1.myworkdayjobs.com/en-US/Careers/job/Prague/Engineer_R-123"));
    }

    @Test
    void siteWithoutLocaleAndHttpUpgradedToHttps() {
        Optional<WorkdayBoard> board = WorkdayBoard.fromCareerUrl(
                "http://2020companies.wd1.myworkdayjobs.com/External_Careers");

        assertEquals(Optional.of(new WorkdayBoard(
                "2020companies", "External_Careers", "https://2020companies.wd1.myworkdayjobs.com")), board);
        assertEquals("2020companies/External_Careers", board.get().slug());
    }

    @Test
    void otherLocalesAndHostCase() {
        assertEquals(Optional.of("AES_ANDES"),
                WorkdayBoard.fromCareerUrl("https://AES.wd1.myworkdayjobs.com/es-CL/AES_ANDES/details/x")
                        .map(WorkdayBoard::site));
        assertEquals(Optional.of("aes"),
                WorkdayBoard.fromCareerUrl("https://AES.wd1.myworkdayjobs.com/es-CL/AES_ANDES")
                        .map(WorkdayBoard::tenant));
    }

    @Test
    void queryStringIgnoredAndRobotsTxtRejected() {
        // Реальные строки страницы 1 индекса CC-MAIN-2026-39.
        assertEquals(Optional.of(new WorkdayBoard(
                        "globalfoundries", "External", "https://globalfoundries.wd1.myworkdayjobs.com")),
                WorkdayBoard.fromCareerUrl("https://globalfoundries.wd1.myworkdayjobs.com/External?q=manufacturing"));
        assertTrue(WorkdayBoard.fromCareerUrl("https://globalfoundries.wd1.myworkdayjobs.com/robots.txt").isEmpty());
        assertEquals(Optional.of("gnw/CareScout"),
                WorkdayBoard.fromCareerUrl("https://gnw.wd1.myworkdayjobs.com/CareScout/job/Richmond-Virginia/"
                        + "Brand-Marketing-Copywriter_REQ-250398-1").map(WorkdayBoard::slug));
    }

    @Test
    void lowercaseRegionLocaleIsNotASite() {
        // §73: в индексе встречается «en-us» — это локаль, а не сайт (иначе POST → 404).
        assertEquals(
                Optional.of(new WorkdayBoard("uline", "Uline", "https://uline.wd1.myworkdayjobs.com")),
                WorkdayBoard.fromCareerUrl("https://uline.wd1.myworkdayjobs.com/en-us/Uline/job/x"));
        assertTrue(WorkdayBoard.fromCareerUrl("https://uline.wd1.myworkdayjobs.com/en-us").isEmpty());
    }

    @Test
    void consecutiveLocalesAreSkipped() {
        // §81: стенд §80 — salesforce/en-us, capitalone/en-Uk уходили в UNREACHABLE.
        assertEquals(Optional.of("External_Career_Site"),
                WorkdayBoard.fromCareerUrl("https://salesforce.wd12.myworkdayjobs.com/en-US/en-us/External_Career_Site/job/x")
                        .map(WorkdayBoard::site));
        assertTrue(WorkdayBoard.fromCareerUrl("https://salesforce.wd12.myworkdayjobs.com/en-US/en-us").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl("https://capitalone.wd12.myworkdayjobs.com/en-US/en-Uk").isEmpty());
    }

    @Test
    void twoLetterUppercaseSiteIsNotALocale() {
        assertEquals(Optional.of("AU"),
                WorkdayBoard.fromCareerUrl("https://american.wd1.myworkdayjobs.com/AU/job/Sydney/x")
                        .map(WorkdayBoard::site));
        assertEquals(Optional.of("AU"),
                WorkdayBoard.fromCareerUrl("https://american.wd1.myworkdayjobs.com/en-US/AU")
                        .map(WorkdayBoard::site));
    }

    @Test
    void dedupKeyIgnoresSiteCase() {
        assertEquals(
                WorkdayBoard.fromCareerUrl("https://aig.wd1.myworkdayjobs.com/en-US/aig").get().dedupKey(),
                WorkdayBoard.fromCareerUrl("https://aig.wd1.myworkdayjobs.com/AIG/job/x").get().dedupKey());
    }

    @Test
    void rejectsNonBoardUrls() {
        assertTrue(WorkdayBoard.fromCareerUrl("https://acme.wd5.myworkdayjobs.com/").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl("https://acme.wd5.myworkdayjobs.com/en-US").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl("https://acme.wd5.myworkdayjobs.com/wday/cxs/acme/x/jobs").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl("https://acme.wd5.myworkdayjobs.com/en-US/login").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl("https://www.myworkdayjobs.com/en-US/Careers").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl("https://acme.example.com/en-US/Careers").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl("ftp://acme.wd5.myworkdayjobs.com/Careers").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl("not a url").isEmpty());
        assertTrue(WorkdayBoard.fromCareerUrl(null).isEmpty());
    }
}
