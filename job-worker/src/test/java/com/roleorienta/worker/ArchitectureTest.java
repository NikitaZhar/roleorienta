package com.roleorienta.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.net.HttpURLConnection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Архитектурные правила job-worker (регламент сессии §6, §86), проверяемые сборкой: ArchUnit
 * читает скомпилированные классы модуля (без тестов) и проверяет зависимости между ними.
 * Нарушение — упавший тест; исключение — только с разрешения владельца и записью причины.
 * https://www.archunit.org/userguide/html/000_Index.html
 */
class ArchitectureTest {

    private static final String ROOT = "com.roleorienta.worker";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /** Пакеты верхнего уровня ({@code discovery}, {@code collect}, …) не зависят друг от друга по кругу. */
    @Test
    void packagesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(CLASSES);
    }

    /**
     * Исходящие HTTP-запросы — только через {@code http.SourceHttpClient}: в нём защита от SSRF,
     * темп на домен, {@code Retry-After} и потолок тела ответа (§57, §71).
     */
    @Test
    void outgoingHttpOnlyThroughSourceHttpClient() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".http..")
                .should().dependOnClassesThat().resideInAPackage("java.net.http..")
                .orShould().dependOnClassesThat().belongToAnyOf(
                        HttpURLConnection.class, RestClient.class, RestTemplate.class)
                .orShould().callMethod(Jsoup.class, "connect", String.class)
                .because("исходящие запросы воркера идут только через SourceHttpClient")
                .check(CLASSES);
    }

    /** Разбор XML — только через {@code xml.SafeXml}: фабрики парсеров с защитой от XXE. */
    @Test
    void xmlParsersOnlyThroughSafeXml() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".xml..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.xml.parsers..",
                        "javax.xml.stream..", "javax.xml.transform..", "javax.xml.validation..", "org.xml.sax..")
                .because("XML разбирается только парсерами, настроенными в SafeXml")
                .check(CLASSES);
    }
}
