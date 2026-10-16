import type { Opened, Work } from './api';

/** Страны работы: «*» — любая страна. */
export function countriesText(work: Work): string {
  return work.countries.length === 0 ? 'not specified'
    : work.countries.map((country) => (country === '*' ? 'any country' : country)).join(', ');
}

/** Дата и время в локали браузера; null — «—». */
export function dateText(value: string | null): string {
  return value === null ? '—' : new Date(value).toLocaleString();
}

/**
 * Дата открытия вакансии (§127): «posted …» — дата размещения работодателем (день UTC, показывается как есть);
 * «found …» — источник даты не дал, день первого обнаружения в локали браузера.
 */
export function openedText(opened: Opened): string {
  const date = new Date(opened.at);
  return opened.byEmployer ? 'posted ' + date.toLocaleDateString(undefined, { timeZone: 'UTC' })
    : 'found ' + date.toLocaleDateString();
}
