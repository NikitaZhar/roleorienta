package com.roleorienta.worker.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Названия работодателей, заданные вручную (§123): запись в источники, проверка файла, недоступность БД при запуске.
 */
class ManualEmployerNamesTests {

    private final SourceRepository sources = mock(SourceRepository.class);

    @Test
    void writesEachEntryToItsSource() throws Exception {
        new ManualEmployerNames(sources, file("""
                {"employers": [
                  {"provider": "workday", "board": "cat.wd5.myworkdayjobs.com/caterpillarcareers", "name": " Caterpillar "},
                  {"provider": "personio", "board": "acme.jobs.personio.de", "name": "Acme"}
                ]}
                """)).apply();

        verify(sources).nameEmployerManually("workday", "cat.wd5.myworkdayjobs.com/caterpillarcareers", "Caterpillar");
        verify(sources).nameEmployerManually("personio", "acme.jobs.personio.de", "Acme");
    }

    @Test
    void rejectsEntryWithoutName() {
        assertThatThrownBy(() -> new ManualEmployerNames(sources, file("""
                {"employers": [{"provider": "workday", "board": "acme.wd3.myworkdayjobs.com/jobs", "name": ""}]}
                """))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unavailableDatabaseDoesNotStopStart() throws Exception {
        when(sources.nameEmployerManually(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        ManualEmployerNames names = new ManualEmployerNames(sources, file("""
                {"employers": [{"provider": "workday", "board": "acme.wd3.myworkdayjobs.com/jobs", "name": "Acme"}]}
                """));

        assertThatCode(names::apply).doesNotThrowAnyException();
    }

    @Test
    void applicationFileIsValid() {
        assertThat(new ManualEmployerNames(sources).entries())
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.board()).doesNotStartWith("http"));
    }

    private static JsonNode file(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }
}
