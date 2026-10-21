import type { HistoryLine, LanguageRequirement, Opened, UserStatus, Work } from './api';
import type { I18n, MessageKey } from './i18n';

/** Страна в строке списка: без подписи поля, поэтому «не указано» называет, что именно (§152). */
export function rowCountryText(work: Work, i18n: I18n): string {
  return work.countryUncertain ? i18n.t('vacancy.countryNotSpecified') : countriesText(work, i18n);
}

/** Страны работы (сведения о вакансии, где есть подпись «Country»): не определена — «not specified»; «*» — любая. */
export function countriesText(work: Work, i18n: I18n): string {
  return work.countryUncertain ? i18n.t('vacancy.notSpecified')
    : work.countries.map((country) => (country === '*' ? i18n.t('vacancy.anyCountry') : country)).join(', ');
}

/**
 * Дата открытия вакансии (§127): дата размещения работодателем — как есть (день UTC); источник даты не дал —
 * «found …», день первого обнаружения; формат даты — по языку интерфейса (§162).
 */
export function openedText(opened: Opened, i18n: I18n): string {
  const date = new Date(opened.at);
  return opened.byEmployer ? date.toLocaleDateString(i18n.locale, { timeZone: 'UTC' })
    : i18n.t('vacancy.found', { date: date.toLocaleDateString(i18n.locale) });
}

/** Дата события по языку интерфейса; нет даты — пусто. */
export function dateText(at: string | null, i18n: I18n): string {
  return at === null ? '' : new Date(at).toLocaleDateString(i18n.locale);
}

/** Подпись статуса вакансии у пользователя (§156). */
export function statusLabel(code: UserStatus['code'], i18n: I18n): string {
  return i18n.t(('status.' + code) as MessageKey);
}

/** Подпись строки истории (§156): статусы, «Получена», «Возвращена», «Закрыта работодателем». */
export function historyLabel(event: HistoryLine['event'], i18n: I18n): string {
  return i18n.t(('history.' + event) as MessageKey);
}

/** Статус в строке «Мои отклики»: «статус · дата», у закрытой работодателем — «· closed» (§147). */
export function statusText(status: UserStatus, i18n: I18n): string {
  return [statusLabel(status.code, i18n), dateText(status.at, i18n), status.closed ? i18n.t('vacancy.closed') : '']
    .filter((part) => part !== '').join(' · ');
}

/** Страны Европейского союза (как регион «EU» в regions.json воркера): такая территория — «from EU» (§158). */
const EU = ['AT', 'BE', 'BG', 'HR', 'CY', 'CZ', 'DK', 'EE', 'FI', 'FR', 'DE', 'GR', 'HU', 'IE', 'IT', 'LV', 'LT', 'LU',
  'MT', 'NL', 'PL', 'PT', 'RO', 'SK', 'SI', 'ES', 'SE'];

/** Подписи формата работы в сведениях (бизнес-описание §6); формат не указан или противоречив — «Not specified». */
const FORMAT_KEYS: Record<NonNullable<Work['format']>, MessageKey> = {
  OFFICE: 'vacancy.onlyOffice',
  REMOTE: 'vacancy.onlyRemote',
  HYBRID: 'vacancy.hybrid',
};

/** Формат работы в сведениях: удалённо — с территорией, если она есть («Only remote — from EU»). */
export function formatText(work: Work, i18n: I18n): string {
  if (work.format === null) {
    return i18n.t('vacancy.formatNotSpecified');
  }
  const format = i18n.t(FORMAT_KEYS[work.format]);
  const territory = work.remoteTerritory ?? [];
  if (territory.length === 0) {
    return format;
  }
  const eu = territory.length === EU.length && EU.every((country) => territory.includes(country));
  return i18n.t('vacancy.from', { format, territory: eu ? 'EU'
    : territory.map((country) => (country === '*' ? i18n.t('vacancy.anyCountry') : country)).join(', ') });
}

/**
 * Требования к языку: «English — required, German — preferred»; не указаны — «not specified» (§158). Названия
 * языков — средствами браузера (Intl.DisplayNames) на языке интерфейса.
 */
export function languagesText(languages: LanguageRequirement[], i18n: I18n): string {
  if (languages.length === 0) {
    return i18n.t('vacancy.notSpecified');
  }
  const names = new Intl.DisplayNames([i18n.locale], { type: 'language' });
  return languages.map((item) => (names.of(item.language) ?? item.language) + ' — '
    + i18n.t(item.required ? 'vacancy.required' : 'vacancy.preferred')).join(', ');
}
