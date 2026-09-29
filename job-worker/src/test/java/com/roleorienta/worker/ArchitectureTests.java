package com.roleorienta.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Архитектурные правила job-worker (технический документ §15, подэтап 1.1), проверяемые каждой
 * сборкой. ArchUnit читает скомпилированные классы и проверяет их зависимости.
 * https://www.archunit.org/userguide/html/000_Index.html
 */
class ArchitectureTests {

    private static final String ROOT = "com.roleorienta.worker";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /**
     * XML разбирается только через {@code SafeXml}: фабрики разборщиков с настройками по умолчанию
     * открыты для атак через внешние сущности (XXE, технический документ §10).
     */
    @Test
    void xmlParsersAreCreatedOnlyBySafeXml() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".xml..")
                .should().dependOnClassesThat().haveFullyQualifiedName("javax.xml.stream.XMLInputFactory")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.xml.parsers.DocumentBuilderFactory")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.xml.parsers.SAXParserFactory")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.xml.transform.TransformerFactory")
                .check(CLASSES);
    }

    /**
     * Пакеты верхнего уровня не зависят друг от друга по кругу.
     */
    @Test
    void packagesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(CLASSES);
    }
}
