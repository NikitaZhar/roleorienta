package com.roleorienta.worker.normalize;


/**
 * Зарплата, найденная в тексте описания (§6, A09), вместе с фрагментом текста, из
 * которого она взята.
 *
 * <p>Фрагмент сохраняется как сырое значение ({@code raw_compensation}) — чтобы
 * пользователь и разбор ошибок видели, на чём основано число, и могли отличить ошибку
 * извлечения от реального отсутствия суммы в оригинале (бизнес-ТЗ, риск качества данных).
 * {@link #ABSENT} — в тексте зарплаты нет (или найденное не прошло правила).</p>
 *
 * @param salary   нормализованная зарплата ({@link NormalizedSalary#ABSENT}, если не найдена)
 * @param fragment сырое значение: фрагмент текста вокруг суммы (или строка зарплаты источника,
 *                 когда зарплата взята из структурного поля — см. {@code SalaryNormalizer#resolve}),
 *                 либо {@code null}
 */
public record ExtractedSalary(NormalizedSalary salary, String fragment) {

    /** Зарплата в тексте не найдена. */
    public static final ExtractedSalary ABSENT = new ExtractedSalary(NormalizedSalary.ABSENT, null);

    /** @return {@code true}, если зарплата найдена */
    public boolean isPresent() {
        return salary.min() != null || salary.max() != null;
    }
}
