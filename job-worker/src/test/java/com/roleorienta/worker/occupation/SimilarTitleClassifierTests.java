package com.roleorienta.worker.occupation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Код по ближайшим примерам (§263): голосуют несколько ближайших суммой сходства — два близких примера одного кода
 * перевешивают один самый близкий другого; при равенстве — код более близкого; ближайший пример дальше порога — «не
 * понять»; векторы в байтах БД читаются теми же.
 */
class SimilarTitleClassifierTests {

    private static final List<TitleClassificationRepository.Labeled> EXAMPLES = List.of(
            labeled("7511", 1, 0),
            labeled("6121", 0.96f, 0.28f),
            labeled("6121", 0.96f, -0.28f),
            labeled("2512", 0, 1));

    @Test
    void neighboursVoteBySimilarity() {
        assertThat(SimilarTitleClassifier.code(new float[] {1, 0}, EXAMPLES, 3, 0.8)).isEqualTo("6121");
        assertThat(SimilarTitleClassifier.code(new float[] {1, 0}, EXAMPLES, 1, 0.8)).isEqualTo("7511");
    }

    @Test
    void tieGoesToNearerExample() {
        List<TitleClassificationRepository.Labeled> tie = List.of(labeled("2512", 0.5f, 0), labeled("2511", 0.75f, 0),
                labeled("2512", 0.25f, 0));

        assertThat(SimilarTitleClassifier.code(new float[] {1, 0}, tie, 3, 0)).isEqualTo("2511");
    }

    @Test
    void farTitleIsNotRecognized() {
        assertThat(SimilarTitleClassifier.code(new float[] {0, -1}, EXAMPLES, 5, 0.8)).isEmpty();
    }

    @Test
    void vectorBytesRoundTrip() {
        float[] vector = {0.25f, -1.5f, 3e-7f};

        assertThat(TitleClassificationRepository.floats(TitleClassificationRepository.bytes(vector)))
                .containsExactly(vector);
    }

    private static TitleClassificationRepository.Labeled labeled(String code, float first, float second) {
        return new TitleClassificationRepository.Labeled(code, new float[] {first, second});
    }
}
