import type { Opened, Work } from './api';

/** Страна в строке списка: без подписи поля, поэтому «не указано» называет, что именно (§152). */
export function rowCountryText(work: Work): string {
  return work.countryUncertain ? 'Country not specified' : countriesText(work);
}

/** Страны работы (сведения о вакансии, где есть подпись «Country»): не определена — «not specified»; «*» — любая. */
export function countriesText(work: Work): string {
  return work.countryUncertain ? 'not specified'
    : work.countries.map((country) => (country === '*' ? 'any country' : country)).join(', ');
}

/**
 * Дата открытия вакансии (§127): дата размещения работодателем — как есть (день UTC); источник даты не дал —
 * «found …», день первого обнаружения в локали браузера (макет §147).
 */
export function openedText(opened: Opened): string {
  const date = new Date(opened.at);
  return opened.byEmployer ? date.toLocaleDateString(undefined, { timeZone: 'UTC' })
    : 'found ' + date.toLocaleDateString();
}
