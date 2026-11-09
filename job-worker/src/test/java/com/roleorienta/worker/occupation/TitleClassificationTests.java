package com.roleorienta.worker.occupation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Задание распознавания позиции (§262, §263) с подменённым распознаванием и примерами: названия без позиции уходят
 * распознаванию, названия с кодом портала — нет; ответ записывается по ключу названия и сразу ставит группу всем
 * открытым публикациям без кода с тем же ключом («ServiceNow Developer» и «… (m/ž)»); пересчёт раз в сутки ответ
 * держит; примеры не готовы — названия не распознаются; модель недоступна — повтор без записи. Примеры с известным
 * кодом: название портала с кодом — пример, вектор записывается и читается тем же. Тест в транзакции с откатом.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class TitleClassificationTests {

    @MockitoBean
    private TitleClassifier classifier;

    @MockitoBean
    private TitleExamples examples;

    @Autowired
    private TitleClassificationRepository repository;

    @Autowired
    private TitleClassificationHandler handler;

    @Autowired
    private TitleOccupationRepository occupations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void modelAnswerRecognizesPostingsWithSameTitle() {
        long source = source();
        long developer = posting(source, "ServiceNow Developer", null);
        long developerMarked = posting(source, "ServiceNow Developer (m/ž)", null);
        long coded = posting(source, "Vodič MKD", "8322");
        when(examples.refresh()).thenReturn(true);
        when(classifier.classify(any())).thenAnswer(call -> {
            List<String> titles = call.getArgument(0);
            assertThat(titles).doesNotContain("Vodič MKD");
            return answers(titles);
        });

        TaskOutcome outcome = handler.handle(new TaskRecord(0, TitleClassificationHandler.TYPE, "{}", 0));

        assertThat(outcome).isInstanceOf(TaskOutcome.Done.class);
        assertThat(recognized(developer)).isEqualTo("2512");
        assertThat(recognized(developerMarked)).isEqualTo("2512");
        assertThat(recognized(coded)).isNull();

        occupations.relearn();

        assertThat(recognized(developer)).isEqualTo("2512");
    }

    @Test
    void unavailableServiceRetriesAndSavesNothing() {
        posting(source(), "ServiceNow Developer", null);
        when(examples.refresh()).thenReturn(true);
        when(classifier.classify(any())).thenThrow(new TitleClassifier.ClassifierUnavailable("HTTP 503"));

        TaskOutcome outcome = handler.handle(new TaskRecord(0, TitleClassificationHandler.TYPE, "{}", 0));

        assertThat(outcome).isInstanceOf(TaskOutcome.Retry.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM title_classification WHERE title_key = "
                + "same_title('ServiceNow Developer')", Integer.class)).isZero();
    }

    @Test
    void titlesWaitForExamples() {
        posting(source(), "ServiceNow Developer", null);
        when(examples.refresh()).thenReturn(false);

        TaskOutcome outcome = handler.handle(new TaskRecord(0, TitleClassificationHandler.TYPE, "{}", 0));

        assertThat(outcome).isInstanceOf(TaskOutcome.Done.class);
        verify(classifier, never()).classify(any());
    }

    /**
     * Позиция profesia.sk без группы (§267) получает группу распознаванием по её названию; решение владельца не
     * перезаписывается.
     */
    @Test
    void profesiaPositionGetsGroupAndOwnerDecisionStays() {
        jdbcTemplate.update("""
                INSERT INTO profesia_position (slug, name) VALUES ('servicenow-test-developer', 'ServiceNow Developer')""");
        jdbcTemplate.update("""
                INSERT INTO profesia_position (slug, name, code, mapped_by) VALUES ('owner-test-position', 'ServiceNow Lead',
                '2511', 'OWNER')""");
        when(examples.refresh()).thenReturn(true);
        when(classifier.classify(any())).thenAnswer(call -> answers(call.getArgument(0)));

        handler.handle(new TaskRecord(0, TitleClassificationHandler.TYPE, "{}", 0));

        assertThat(jdbcTemplate.queryForList("""
                SELECT slug || ' ' || code || ' ' || mapped_by FROM profesia_position
                WHERE slug IN ('servicenow-test-developer', 'owner-test-position') ORDER BY slug""", String.class))
                .containsExactly("owner-test-position 2511 OWNER", "servicenow-test-developer 2512 bge-m3");
    }

    /**
     * Позиция, которую распознавание не поняло, распознаётся снова через неделю (§271, аудит §270 замечание 7), более
     * свежая — нет; позиции и названия — одним вызовом распознавания (замечание 12).
     */
    @Test
    void notUnderstoodProfesiaPositionIsAskedAgainAfterAWeek() {
        jdbcTemplate.update("""
                INSERT INTO profesia_position (slug, name, mapped_by, mapped_at)
                VALUES ('servicenow-old-unknown', 'ServiceNow Old Unknown', 'bge-m3', now() - interval '8 days'),
                       ('servicenow-new-unknown', 'ServiceNow New Unknown', 'bge-m3', now())""");
        posting(source(), "ServiceNow Developer", null);
        when(examples.refresh()).thenReturn(true);
        when(classifier.classify(any())).thenAnswer(call -> answers(call.getArgument(0)));

        handler.handle(new TaskRecord(0, TitleClassificationHandler.TYPE, "{}", 0));

        assertThat(jdbcTemplate.queryForList("""
                SELECT slug || ' ' || coalesce(code, '-') FROM profesia_position
                WHERE slug IN ('servicenow-old-unknown', 'servicenow-new-unknown') ORDER BY slug""", String.class))
                .containsExactly("servicenow-new-unknown -", "servicenow-old-unknown 2512");
        verify(classifier, times(1)).classify(any());
    }

    @Test
    void portalTitleWithCodeIsExampleAndKeepsVector() {
        posting(source(), "Vodič MKD (muž/žena)", "8322");
        TitleClassificationRepository.Example driver = new TitleClassificationRepository.Example("PORTAL",
                jdbcTemplate.queryForObject("SELECT same_title('Vodič MKD (muž/žena)')", String.class),
                "Vodič MKD (muž/žena)", "8322");
        assertThat(repository.pendingExamples("test-model", 1000)).contains(driver);

        repository.saveExamples(List.of(driver), new float[][] {{0.6f, -0.8f}}, "test-model");

        assertThat(repository.pendingExamples("test-model", 1000)).doesNotContain(driver);
        assertThat(repository.examples("test-model")).singleElement().satisfies(example -> {
            assertThat(example.code()).isEqualTo("8322");
            assertThat(example.portal()).isTrue();
            assertThat(example.vector()).containsExactly(0.6f, -0.8f);
        });
    }

    private static Map<String, String> answers(List<String> titles) {
        Map<String, String> answers = new HashMap<>();
        titles.forEach(title -> answers.put(title, title.startsWith("ServiceNow") ? "2512" : ""));
        return answers;
    }

    private long source() {
        return jdbcTemplate.queryForObject("INSERT INTO source (provider, board) VALUES ('careerpage', "
                + "'classification-test') RETURNING id", Long.class);
    }

    private long posting(long source, String title, String code) {
        long vacancy = jdbcTemplate.queryForObject("""
                INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at)
                VALUES ('ACTIVE', ?, 'https://classification.example', now(), now()) RETURNING id""", Long.class,
                title);
        return jdbcTemplate.queryForObject("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at,
                                         occupation_code)
                VALUES (?, ?, ?, ?, 'https://classification.example', now(), now(), ?) RETURNING id""", Long.class,
                source, vacancy, "classification-" + vacancy, title, code);
    }

    private String recognized(long posting) {
        return jdbcTemplate.queryForObject("SELECT title_occupation_code FROM job_posting WHERE id = ?", String.class,
                posting);
    }
}
