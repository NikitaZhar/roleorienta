package com.roleorienta.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/**
 * Архитектурные правила job-api (регламент сессии §6, §86), проверяемые сборкой: ArchUnit
 * читает скомпилированные классы модуля (без тестов) и проверяет зависимости между ними.
 * Нарушение — упавший тест; исключение — только с разрешения владельца и записью причины.
 * https://www.archunit.org/userguide/html/000_Index.html
 */
class ArchitectureTest {

    private static final String ROOT = "com.roleorienta.api";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /** Пакеты верхнего уровня ({@code posting}, {@code saved}, …) не зависят друг от друга по кругу. */
    @Test
    void packagesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(CLASSES);
    }

    /** Контроллеры — только вход/выход (контракт §3.3): данные читают через сервисы, не репозитории. */
    @Test
    void controllersDoNotUseRepositories() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAssignableTo(Repository.class)
                .because("контроллер делегирует сервису, доступ к данным — в репозиториях за сервисом")
                .check(CLASSES);
    }
}
