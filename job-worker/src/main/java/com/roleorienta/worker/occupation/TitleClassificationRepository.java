package com.roleorienta.worker.occupation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Названия к распознаванию, ответы ({@code title_classification}, V93) и примеры с известным кодом
 * ({@code title_example}, V94); стенограмма §262, §263. Ключ названия — {@code same_title} (V92): одинаковые названия
 * разных вакансий распознаются один раз.
 */
@Repository
public class TitleClassificationRepository {

    /** Позиция profesia.sk, которую распознавание не поняло, распознаётся снова через столько дней (§271). */
    static final int RETRY_UNMAPPED_DAYS = 7;

    /** Позиция profesia.sk к распознаванию: не сопоставлена или ответ «не понять» не моложе срока (не владельца). */
    private static final String UNMAPPED = "(mapped_by IS NULL OR code IS NULL AND mapped_by <> 'OWNER' "
            + "AND mapped_at < now() - make_interval(days => " + RETRY_UNMAPPED_DAYS + "))";

    /**
     * Ключ открытой публикации, позиции которой нет: ни кода портала, ни распознанной группы, ни позиции словаря у её
     * вакансии — и ключа ещё нет среди ответов.
     */
    private static final String UNRECOGNIZED = """
            FROM job_posting p
            WHERE p.closed_at IS NULL AND p.occupation_code IS NULL AND p.title_occupation_code IS NULL
              AND NOT EXISTS (SELECT 1 FROM vacancy_position_match m WHERE m.vacancy_id = p.vacancy_id)
              AND same_title(p.title) <> ''
              AND NOT EXISTS (SELECT 1 FROM title_classification c WHERE c.title_key = same_title(p.title))""";

    /** Длина названия портала для примера не больше (ключ примера — часть первичного ключа). */
    private static final int MAX_EXAMPLE_LENGTH = 300;

    /** Строк примеров за один приём из БД (векторы — около 4 КБ каждый). */
    private static final int FETCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public TitleClassificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Названия без позиции: по одному названию на ключ, сначала ключи, общие для большего числа открытых публикаций
     * (больше вакансий станет видно за один ответ).
     *
     * @param limit сколько
     * @return названия, как в публикации
     */
    public List<String> unrecognized(int limit) {
        return jdbcTemplate.queryForList("SELECT min(p.title) " + UNRECOGNIZED
                + " GROUP BY same_title(p.title) ORDER BY count(*) DESC, min(p.title) LIMIT ?", String.class, limit);
    }

    /**
     * Записывает ответы и сразу проставляет распознанную группу открытым публикациям без кода портала и без
     * распознанной группы с теми же ключами — одной транзакцией (выдача видит их без ожидания пересчёта раз в сутки,
     * §215; пересчёт держит их тем же правилом — {@link TitleOccupationRepository#relearn}).
     *
     * @param answers название → код группы (пусто — не распознано)
     * @param model   каким способом распознано
     * @return у скольких публикаций появилась распознанная группа
     */
    @Transactional
    public int save(Map<String, String> answers, String model) {
        answers.forEach((title, code) -> jdbcTemplate.update("""
                INSERT INTO title_classification (title_key, code, model) VALUES (same_title(?), ?, ?)
                ON CONFLICT (title_key) DO NOTHING
                """, title, code.isEmpty() ? null : code, model));
        return jdbcTemplate.update("""
                UPDATE job_posting p SET title_occupation_code = c.code
                FROM title_classification c
                WHERE c.code IS NOT NULL AND c.title_key = same_title(p.title)
                  AND p.closed_at IS NULL AND p.occupation_code IS NULL AND p.title_occupation_code IS NULL
                  AND c.title_key = ANY (SELECT same_title(t) FROM unnest(CAST(? AS TEXT[])) t)
                """, (PreparedStatement statement) -> statement.setArray(1,
                        statement.getConnection().createArrayOf("text", answers.keySet().toArray())));
    }

    /**
     * @return позиции каталога profesia.sk без группы ISCO-08 (§267): ключ и название; ещё не сопоставленные и те, что
     *         распознавание не поняло, — снова через {@value #RETRY_UNMAPPED_DAYS} дн. (§271, аудит §270 замечание 7:
     *         примеров с кодом становится больше); сопоставленные владельцем ({@code OWNER}) — нет
     */
    public Map<String, String> unmappedProfesiaPositions() {
        Map<String, String> positions = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT slug, name FROM profesia_position WHERE " + UNMAPPED + " ORDER BY slug",
                row -> {
                    positions.put(row.getString(1), row.getString(2));
                });
        return positions;
    }

    /**
     * Группы ISCO-08 позиций profesia.sk по ответу распознавания (§267; способ — {@code mapped_by}); позицию, которую
     * уже сопоставил владелец или прежний ответ с группой, не трогает; ответ «не понять» заменяет через
     * {@value #RETRY_UNMAPPED_DAYS} дн.
     *
     * @param codes ключ позиции → код группы (пусто — не распознано)
     * @param model каким способом распознано
     */
    @Transactional
    public void mapProfesiaPositions(Map<String, String> codes, String model) {
        codes.forEach((slug, code) -> jdbcTemplate.update("""
                UPDATE profesia_position SET code = ?, mapped_by = ?, mapped_at = now()
                WHERE slug = ? AND """ + UNMAPPED, code.isEmpty() ? null : code, model, slug));
    }

    /**
     * Примеры с известным кодом, вектора которых этой модели ещё нет: названия вакансий портала с одним кодом
     * профессии на ключ (§203; ключ с разными кодами — не пример) и названия профессий ESCO на словацком и английском
     * (V75). Сначала портал — названия того же языка и вида, что у распознаваемых вакансий.
     *
     * @param model модель векторов
     * @param limit сколько
     * @return примеры без вектора
     */
    public List<Example> pendingExamples(String model, int limit) {
        return jdbcTemplate.query("""
                SELECT e.source, e.example, e.text, e.code FROM (
                    SELECT 'PORTAL' AS source, same_title(p.title) AS example, min(p.title) AS text,
                           min(p.occupation_code) AS code
                    FROM job_posting p
                    WHERE p.occupation_code IS NOT NULL AND same_title(p.title) <> '' AND length(p.title) <= ?
                    GROUP BY same_title(p.title) HAVING count(DISTINCT p.occupation_code) = 1
                    UNION
                    SELECT 'ESCO', l.label, l.label, o.group_code
                    FROM esco_label l JOIN esco_occupation o ON o.id = l.occupation_id
                    WHERE l.language IN ('sk', 'en')) e
                WHERE NOT EXISTS (SELECT 1 FROM title_example x WHERE x.source = e.source AND x.example = e.example
                                    AND x.code = e.code AND x.model = ?)
                ORDER BY e.source DESC, e.example LIMIT ?
                """, (row, number) -> new Example(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4)), MAX_EXAMPLE_LENGTH, model, limit);
    }

    /**
     * Записывает векторы примеров (вектор прежней модели заменяется).
     *
     * @param examples примеры
     * @param vectors  вектор каждого примера, в том же порядке
     * @param model    модель векторов
     */
    @Transactional
    public void saveExamples(List<Example> examples, float[][] vectors, String model) {
        List<Object[]> rows = new ArrayList<>(examples.size());
        for (int index = 0; index < examples.size(); index++) {
            Example example = examples.get(index);
            rows.add(new Object[] {example.source(), example.example(), example.code(), example.text(), model,
                bytes(vectors[index])});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO title_example (source, example, code, text, model, vector) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, example, code)
                DO UPDATE SET text = EXCLUDED.text, model = EXCLUDED.model, vector = EXCLUDED.vector,
                              created_at = now()
                """, rows);
    }

    /**
     * Все примеры этой модели с векторами — для поиска ближайших (около 50 тыс., около 200 МБ в памяти). Строки
     * читаются порциями ({@code fetchSize} действует только внутри транзакции — драйвер PostgreSQL иначе читает всё
     * сразу). https://jdbc.postgresql.org/documentation/query/#getting-results-based-on-a-cursor
     *
     * @param model модель векторов
     * @return код, источник и вектор каждого примера
     */
    @Transactional(readOnly = true)
    public List<Labeled> examples(String model) {
        List<Labeled> examples = new ArrayList<>();
        jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT code, source = 'PORTAL', vector FROM title_example WHERE model = ?");
            statement.setFetchSize(FETCH_SIZE);
            statement.setString(1, model);
            return statement;
        }, row -> {
            examples.add(new Labeled(row.getString(1), row.getBoolean(2), floats(row.getBytes(3))));
        });
        return examples;
    }

    /** Вектор в байты: числа float по 4 байта, младший байт первым. */
    static byte[] bytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    /** Байты в вектор — обратно {@link #bytes}. */
    static float[] floats(byte[] bytes) {
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    /**
     * Пример с известным кодом.
     *
     * @param source  {@code PORTAL} — название вакансии портала с кодом профессии; {@code ESCO} — название профессии
     * @param example ключ: у портала — {@code same_title} названия, у ESCO — название
     * @param text    текст, вектор которого ищется
     * @param code    группа ISCO-08
     */
    public record Example(String source, String example, String text, String code) {
    }

    /**
     * Пример для поиска ближайших.
     *
     * @param code   группа ISCO-08
     * @param portal пример — название вакансии портала (иначе — название профессии ESCO; у них разный порог сходства)
     * @param vector вектор длины 1
     */
    public record Labeled(String code, boolean portal, float[] vector) {
    }
}
