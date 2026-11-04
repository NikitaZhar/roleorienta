package com.roleorienta.worker.occupation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * Распознавание позиции моделью (§262) с подменённым распознаванием: названия без позиции уходят модели вместе с
 * названиями портала с кодом (мера точности); ответ записывается по ключу названия и сразу ставит группу всем открытым
 * публикациям без кода с тем же ключом («ServiceNow Developer» и «… (m/ž)»); публикации с кодом портала группу не
 * получают; пересчёт раз в сутки ответ модели держит; модель недоступна — повтор без записи. Тест в транзакции с
 * откатом.
 */
@SpringBootTest(properties = {"app.title-classification.limits.batch-size=1000", "app.title-classification.limits.check-per-batch=500",
        "app.title-classification.limits.batches-per-task=1"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class TitleClassificationTests {

    @MockitoBean
    private TitleClassifier classifier;

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
        when(classifier.classify(any(), any())).thenAnswer(call -> answers(call.getArgument(0)));

        TaskOutcome outcome = handler.handle(new TaskRecord(0, TitleClassificationHandler.TYPE, "{}", 0));

        assertThat(outcome).isInstanceOf(TaskOutcome.Done.class);
        assertThat(recognized(developer)).isEqualTo("2512");
        assertThat(recognized(developerMarked)).isEqualTo("2512");
        assertThat(recognized(coded)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT code FROM title_classification WHERE title_key = "
                + "same_title('Vodič MKD')", String.class)).isEqualTo("8322");

        occupations.relearn();

        assertThat(recognized(developer)).isEqualTo("2512");
    }

    @Test
    void unavailableServiceRetriesAndSavesNothing() {
        posting(source(), "ServiceNow Developer", null);
        when(classifier.classify(any(), any())).thenThrow(new TitleClassifier.ClassifierUnavailable("HTTP 503"));

        TaskOutcome outcome = handler.handle(new TaskRecord(0, TitleClassificationHandler.TYPE, "{}", 0));

        assertThat(outcome).isInstanceOf(TaskOutcome.Retry.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM title_classification WHERE title_key = "
                + "same_title('ServiceNow Developer')", Integer.class)).isZero();
    }

    private static Map<String, String> answers(List<String> titles) {
        Map<String, String> answers = new HashMap<>();
        titles.forEach(title -> answers.put(title, title.startsWith("ServiceNow") ? "2512"
                : title.startsWith("Vodič") ? "8322" : ""));
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
