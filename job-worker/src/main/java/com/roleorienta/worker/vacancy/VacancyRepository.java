package com.roleorienta.worker.vacancy;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к вакансиям.
 */
public interface VacancyRepository extends JpaRepository<Vacancy, Long> {
}
