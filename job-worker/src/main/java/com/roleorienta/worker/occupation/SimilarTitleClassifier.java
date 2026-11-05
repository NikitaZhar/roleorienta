package com.roleorienta.worker.occupation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Распознавание позиции по похожему названию с известным кодом ({@link TitleClassifier}; стенограмма §263): название
 * вакансии получает вектор ({@link OllamaEmbedder}), среди примеров ({@code title_example}: названия вакансий портала
 * с кодом профессии и названия профессий ESCO) ищутся ближайшие по сходству, код — тот, за который больше суммарное
 * сходство ближайших ({@code limits.neighbours}). Сходство с самым близким примером ниже {@code limits.min-similarity}
 * — «не понять» (пустое значение): у «Find out more», «Apply Now» похожих примеров нет.
 *
 * <p>Замер §263 на 200 названиях портала с кодом (сами они из примеров исключены): совпадение по 4 знакам ISCO-08 —
 * 59 %, по 2 знакам — 66 %; языковая модель Qwen 2.5 7B на тех же названиях — 14 % и 36 %. Примеров портала с
 * каждым днём больше — точность растёт без правки кода. Ответ на одно и то же название не меняется, пока не
 * меняются примеры.</p>
 *
 * <p>Ближайшие ищутся перебором всех примеров (около 50 тыс. векторов, несколько миллиардов умножений на 5 000
 * названий — названия считаются параллельно на всех ядрах, {@link IntStream#parallel()}); отдельный поисковый индекс
 * и расширение PostgreSQL не нужны (решение владельца 2026-11-05).</p>
 */
@Component
public class SimilarTitleClassifier implements TitleClassifier {

    private final TitleClassificationRepository repository;
    private final OllamaEmbedder embedder;
    private final TitleClassificationProperties properties;

    /**
     * @param repository примеры с векторами
     * @param embedder   векторы названий
     * @param properties модель, число голосующих, порог сходства
     */
    public SimilarTitleClassifier(TitleClassificationRepository repository, OllamaEmbedder embedder,
            TitleClassificationProperties properties) {
        this.repository = repository;
        this.embedder = embedder;
        this.properties = properties;
    }

    @Override
    public Map<String, String> classify(List<String> titles) {
        if (titles.isEmpty()) {
            return Map.of();
        }
        List<TitleClassificationRepository.Labeled> examples = repository.examples(properties.model());
        if (examples.isEmpty()) {
            throw new ClassifierUnavailable("No examples with vectors of " + properties.model() + " yet");
        }
        float[][] vectors = embedder.embed(titles);
        TitleClassificationProperties.Limits limits = properties.limits();
        List<String> codes = IntStream.range(0, titles.size()).parallel()
                .mapToObj(index -> code(vectors[index], examples, limits.neighbours(), limits.minSimilarity()))
                .toList();
        Map<String, String> answers = new HashMap<>();
        for (int index = 0; index < titles.size(); index++) {
            answers.put(titles.get(index), codes.get(index));
        }
        return answers;
    }

    /**
     * Код по ближайшим примерам: голосование суммой сходства; при равенстве — код более близкого примера.
     *
     * @param vector        вектор названия (длины 1)
     * @param examples      примеры с векторами длины 1
     * @param neighbours    сколько ближайших голосуют
     * @param minSimilarity сходство с ближайшим не ниже — иначе «не понять»
     * @return код группы или пусто
     */
    static String code(float[] vector, List<TitleClassificationRepository.Labeled> examples, int neighbours,
            double minSimilarity) {
        int[] nearest = new int[neighbours];
        double[] similarity = new double[neighbours];
        Arrays.fill(similarity, Double.NEGATIVE_INFINITY);
        for (int example = 0; example < examples.size(); example++) {
            double value = dot(vector, examples.get(example).vector());
            if (value > similarity[neighbours - 1]) {
                int place = neighbours - 1;
                while (place > 0 && value > similarity[place - 1]) {
                    similarity[place] = similarity[place - 1];
                    nearest[place] = nearest[place - 1];
                    place--;
                }
                similarity[place] = value;
                nearest[place] = example;
            }
        }
        if (similarity[0] < minSimilarity) {
            return "";
        }
        Map<String, Double> votes = new LinkedHashMap<>();
        for (int place = 0; place < neighbours && similarity[place] > Double.NEGATIVE_INFINITY; place++) {
            votes.merge(examples.get(nearest[place]).code(), similarity[place], Double::sum);
        }
        String best = null;
        for (Map.Entry<String, Double> vote : votes.entrySet()) {
            if (best == null || vote.getValue() > votes.get(best)) {
                best = vote.getKey();
            }
        }
        return best;
    }

    private static double dot(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new ClassifierUnavailable("Vector sizes differ: " + left.length + " and " + right.length);
        }
        double sum = 0;
        for (int index = 0; index < left.length; index++) {
            sum += left[index] * right[index];
        }
        return sum;
    }
}
