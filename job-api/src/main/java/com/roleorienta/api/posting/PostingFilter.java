package com.roleorienta.api.posting;

import com.roleorienta.core.domain.SeniorityLevel;
import com.roleorienta.core.domain.WorkModality;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Необязательные фильтры ленты публикаций (§7.3). Любое поле {@code null} — фильтр по
 * нему не применяется. Сгруппированы в один объект, чтобы методы ленты не превышали
 * лимит «≤5 параметров» (контракт §3.10); Spring MVC связывает одноимённые query-параметры
 * с полями записи.
 *
 * <p>Только скалярные нормализованные поля публикации (одна таблица):</p>
 * <ul>
 *   <li>{@code country} — основная страна публикации <b>или</b> страна одной из доп. локаций
 *       (§68: IQVIA «UK + Bratislava» находится по Slovakia). Доп. локации хранятся строкой
 *       «Город, [Регион,] Страна; …» — совпадением считается страна как последний сегмент
 *       записи, а не подстрока («India» не совпадает с «Indiana»).</li>
 *   <li>{@code minSalary} — верхняя граница зарплаты не ниже значения, а если верхней нет
 *       («от X», §66) — нижняя: {@code coalesce(salary_max, salary_min) >= minSalary}. Валюты
 *       и периоды не сравниваются между собой (межвалютное/межпериодное сравнение — отдельный
 *       вопрос, A09).</li>
 *   <li>{@code postedFrom} — дата публикации по источнику не раньше указанной
 *       (формат {@code YYYY-MM-DD}); публикации без даты под фильтр не попадают.</li>
 * </ul>
 * <p>Публикации с {@code null} в фильтруемом поле в выборку по этому фильтру не попадают
 * (показ «неизвестных» по явному выбору — §7.3 — отдельный срез). Фильтр по языку/навыку
 * (нужен join) — отдельный срез.</p>
 *
 * @param workModality формат работы (REMOTE/HYBRID/ONSITE/UNKNOWN) или {@code null}
 * @param seniority    уровень опыта (JUNIOR/MEDIOR/SENIOR/UNKNOWN) или {@code null}
 * @param country      страна (регистронезависимо, основная или доп. локация) или {@code null}
 * @param minSalary    нижний порог зарплаты (с верхней границей, без неё — с нижней) или {@code null}
 * @param postedFrom   самая ранняя дата публикации или {@code null}
 */
public record PostingFilter(WorkModality workModality, SeniorityLevel seniority,
                            String country, BigDecimal minSalary,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate postedFrom) {
}
