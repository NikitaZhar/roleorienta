package com.roleorienta.api.posting;

import com.roleorienta.core.domain.SeniorityLevel;
import com.roleorienta.core.domain.WorkModality;
import java.math.BigDecimal;

/**
 * Необязательные фильтры ленты публикаций (§7.3). Любое поле {@code null} — фильтр по
 * нему не применяется. Сгруппированы в один объект, чтобы методы ленты не превышали
 * лимит «≤5 параметров» (контракт §3.10); Spring MVC связывает одноимённые query-параметры
 * с полями записи.
 *
 * <p>Только скалярные нормализованные поля публикации (одна таблица). {@code minSalary}
 * отбирает публикации, у которых верхняя граница зарплаты не ниже значения
 * ({@code salary_max >= minSalary}); валюты между собой не сравниваются (в пилоте данные
 * в одной валюте — межвалютное сравнение отдельный вопрос). Публикации с {@code null} в
 * фильтруемом поле в выборку по этому фильтру не попадают (показ «неизвестных» по явному
 * выбору — §7.3 — отдельный срез). Фильтр по языку/навыку (нужен join) — отдельный срез.</p>
 *
 * @param workModality формат работы (REMOTE/HYBRID/UNKNOWN) или {@code null}
 * @param seniority    уровень опыта (JUNIOR/MEDIOR/SENIOR/UNKNOWN) или {@code null}
 * @param country      страна (регистронезависимо) или {@code null}
 * @param minSalary    минимальная верхняя граница зарплаты или {@code null}
 */
public record PostingFilter(WorkModality workModality, SeniorityLevel seniority,
                            String country, BigDecimal minSalary) {
}
