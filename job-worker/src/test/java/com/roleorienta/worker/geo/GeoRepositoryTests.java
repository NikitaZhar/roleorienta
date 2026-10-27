package com.roleorienta.worker.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Версия правил загрузки справочника городов (§210, аудит §211): справочник по другой версии — не текущий, загрузка
 * ставится заново; после замены — текущий. Тест в транзакции, которая откатывается: общий справочник других тестов не
 * меняется.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class GeoRepositoryTests {

    @Autowired
    private GeoRepository repository;

    @Test
    void cityListIsCurrentOnlyForItsRules() {
        repository.replace(List.of(new GeoCity("warszawa", "PL", 1_702_139)), "test-old");

        assertThat(repository.isCurrent("test-old")).isTrue();
        assertThat(repository.isCurrent(GeoImportHandler.RULES)).isFalse();

        repository.replace(List.of(new GeoCity("warszawa", "PL", 1_702_139)), GeoImportHandler.RULES);
        assertThat(repository.isCurrent(GeoImportHandler.RULES)).isTrue();
    }
}
