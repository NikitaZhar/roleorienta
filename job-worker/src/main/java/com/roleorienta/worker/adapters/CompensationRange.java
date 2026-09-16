package com.roleorienta.worker.adapters;

import java.math.BigDecimal;

/**
 * Структурированный зарплатный диапазон, разобранный адаптером из полей источника
 * (сырьё для нормализации, §6). Суммы — в единицах валюты (не в центах).
 *
 * <p>Только то, что источник действительно сообщил: суммы и валюта. Период и база
 * (gross/net) здесь не выводятся — их определяет нормализатор, и если источник о них
 * молчит, они станут {@code UNKNOWN}. Отсутствующее поле — {@code null}.</p>
 *
 * @param minAmount нижняя граница суммы или {@code null}
 * @param maxAmount верхняя граница суммы или {@code null}
 * @param currency  код валюты (напр. {@code EUR}) или {@code null}
 */
public record CompensationRange(BigDecimal minAmount, BigDecimal maxAmount, String currency) {

    /** Диапазон без сумм считается пустым (зарплата фактически не указана). */
    public boolean isEmpty() {
        return minAmount == null && maxAmount == null;
    }
}
