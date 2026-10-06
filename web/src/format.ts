import type { Work } from './api';

/** Страны работы: «*» — любая страна. */
export function countriesText(work: Work): string {
  return work.countries.length === 0 ? 'not specified'
    : work.countries.map((country) => (country === '*' ? 'any country' : country)).join(', ');
}

/** Формат работы; не указан — «not specified». */
export function formatText(work: Work): string {
  return work.format === null ? 'not specified' : work.format.charAt(0) + work.format.substring(1).toLowerCase();
}

/** Дата и время в локали браузера; null — «—». */
export function dateText(value: string | null): string {
  return value === null ? '—' : new Date(value).toLocaleString();
}
