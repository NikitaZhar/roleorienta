package com.roleorienta.worker.occupation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Код по ближайшим примерам (§263, §264): голосуют несколько ближайших суммой сходства — два близких примера одного
 * кода перевешивают один самый близкий другого; при равенстве — код более близкого; ближайший пример дальше порога —
 * «не понять»; у примера ESCO порог выше, чем у примера портала; векторы в байтах БД читаются теми же.
 */
class SimilarTitleClassifierTests {

    private static final List<TitleClassificationRepository.Labeled> EXAMPLES = List.of(
            portal("7511", 1, 0),
            portal("6121", 0.96f, 0.28f),
            portal("6121", 0.96f, -0.28f),
            portal("2512", 0, 1));

    @Test
    void neighboursVoteBySimilarity() {
        assertThat(SimilarTitleClassifier.code(new float[] {1, 0}, EXAMPLES, limits(3))).isEqualTo("6121");
        assertThat(SimilarTitleClassifier.code(new float[] {1, 0}, EXAMPLES, limits(1))).isEqualTo("7511");
    }

    @Test
    void tieGoesToNearerExample() {
        List<TitleClassificationRepository.Labeled> tie = List.of(portal("2512", 0.5f, 0), portal("2511", 0.75f, 0),
                portal("2512", 0.25f, 0));
        TitleClassificationProperties.Limits anySimilarity = new TitleClassificationProperties.Limits(10, 10, 3, 0, 0);

        assertThat(SimilarTitleClassifier.code(new float[] {1, 0}, tie, anySimilarity)).isEqualTo("2511");
    }

    @Test
    void farTitleIsNotRecognized() {
        assertThat(SimilarTitleClassifier.code(new float[] {0, -1}, EXAMPLES, limits(5))).isEmpty();
    }

    @Test
    void escoExampleNeedsHigherSimilarity() {
        float[] title = {1, 0};
        List<TitleClassificationRepository.Labeled> esco = List.of(
                new TitleClassificationRepository.Labeled("5223", false, new float[] {0.85f, 0.53f}));
        List<TitleClassificationRepository.Labeled> close = List.of(
                new TitleClassificationRepository.Labeled("5223", false, new float[] {0.95f, 0.31f}));

        assertThat(SimilarTitleClassifier.code(title, esco, limits(5))).isEmpty();
        assertThat(SimilarTitleClassifier.code(title, close, limits(5))).isEqualTo("5223");
        assertThat(SimilarTitleClassifier.code(title, List.of(portal("5223", 0.85f, 0.53f)), limits(5)))
                .isEqualTo("5223");
    }

    @Test
    void vectorBytesRoundTrip() {
        float[] vector = {0.25f, -1.5f, 3e-7f};

        assertThat(TitleClassificationRepository.floats(TitleClassificationRepository.bytes(vector)))
                .containsExactly(vector);
    }

    private static TitleClassificationProperties.Limits limits(int neighbours) {
        return new TitleClassificationProperties.Limits(10, 10, neighbours, 0.8, 0.9);
    }

    private static TitleClassificationRepository.Labeled portal(String code, float first, float second) {
        return new TitleClassificationRepository.Labeled(code, true, new float[] {first, second});
    }
}
