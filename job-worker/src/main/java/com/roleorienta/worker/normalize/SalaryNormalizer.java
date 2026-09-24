package com.roleorienta.worker.normalize;

import com.roleorienta.core.domain.SalaryBasis;
import com.roleorienta.core.domain.SalaryPeriod;
import com.roleorienta.worker.adapters.CompensationRange;
import org.springframework.stereotype.Component;

/**
 * Нормализатор зарплаты (§6, A09): приводит структурированный диапазон источника к
 * {@link NormalizedSalary}, соблюдая правило «валюта, период и база не смешиваются,
 * неизвестное — явно».
 *
 * <p>Из зарплатного диапазона Greenhouse ({@code pay_input_ranges}) известны только
 * суммы и валюта; период и база там не сообщаются, поэтому ставятся {@code UNKNOWN}
 * (не угадываются). Если диапазона нет — {@link NormalizedSalary#ABSENT}. Логика
 * вынесена в отдельный компонент, чтобы правила нормализации были в одном месте и
 * покрывались модульными тестами независимо от обработчика.</p>
 *
 * <p>{@link #resolve} выбирает источник зарплаты: структурный диапазон главнее, при его
 * отсутствии (у Workday структурного поля нет) — сумма из текста описания
 * ({@link SalaryTextExtractor}, §66).</p>
 */
@Component
public class SalaryNormalizer {

    private final SalaryTextExtractor textExtractor;

    /**
     * @param textExtractor извлечение зарплаты из текста описания (запасной путь)
     */
    public SalaryNormalizer(SalaryTextExtractor textExtractor) {
        this.textExtractor = textExtractor;
    }

    /**
     * Зарплата публикации: структурный диапазон источника, а если его нет — из текста
     * описания.
     *
     * @param range           структурированный диапазон из адаптера или {@code null}
     * @param rawCompensation сырая строка зарплаты источника или {@code null}
     * @param description     текст описания или {@code null}
     * @return зарплата и её сырое значение: строка источника (структурный путь) или
     *         фрагмент текста вокруг суммы (текстовый путь); {@link ExtractedSalary#ABSENT}
     *         с {@code rawCompensation}, если суммы нет нигде
     */
    public ExtractedSalary resolve(CompensationRange range, String rawCompensation, String description) {
        NormalizedSalary structured = normalize(range);
        if (!NormalizedSalary.ABSENT.equals(structured)) {
            return new ExtractedSalary(structured, rawCompensation);
        }
        ExtractedSalary fromText = textExtractor.extract(description);
        return fromText.isPresent() ? fromText : new ExtractedSalary(NormalizedSalary.ABSENT, rawCompensation);
    }

    /**
     * @param range структурированный зарплатный диапазон из адаптера или {@code null}
     * @return нормализованная зарплата ({@link NormalizedSalary#ABSENT}, если суммы нет)
     */
    public NormalizedSalary normalize(CompensationRange range) {
        if (range == null || range.isEmpty()) {
            return NormalizedSalary.ABSENT;
        }
        return new NormalizedSalary(
                range.minAmount(),
                range.maxAmount(),
                range.currency(),
                SalaryPeriod.UNKNOWN,
                SalaryBasis.UNKNOWN);
    }
}
