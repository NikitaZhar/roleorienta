import type { HistoryLine, LanguageRequirement, Opened, UserStatus, Work } from './api';
import type { I18n, MessageKey } from './i18n';

/**
 * «Работодатель · страна» в строке списка; чего нет, то не показывается (§192, принцип владельца 2026-10-26: нет
 * информации — показываем то, что есть).
 */
export function rowMetaText(employer: string | null, work: Work, i18n: I18n): string {
  return [employer, work.countryUncertain ? null : countriesText(work, i18n)]
    .filter((part) => part !== null && part !== '').join(' · ');
}

/** Страны работы названиями; «*» — любая. Не определена — не показывается (§192). */
export function countriesText(work: Work, i18n: I18n): string {
  return countryNames(work.countries, i18n);
}

/**
 * Страны названиями на языке интерфейса (Intl.DisplayNames браузера, §162; аудит §165): «*» — любая страна; код,
 * которого браузер не знает, — как есть.
 */
function countryNames(codes: string[], i18n: I18n): string {
  const names = new Intl.DisplayNames([i18n.locale], { type: 'region', fallback: 'code' });
  return codes.map((code) => (code === '*' ? i18n.t('vacancy.anyCountry') : regionName(names, code))).join(', ');
}

/** Название страны; код не по форме ISO 3166 ({@code of} бросает RangeError) — как есть. */
function regionName(names: Intl.DisplayNames, code: string): string {
  try {
    return names.of(code) ?? code;
  } catch {
    return code;
  }
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

/**
 * Короткая дата в строке списка (§179, §180): «14 Oct» — месяц словом, год — только если не текущий.
 *
 * @param zone часовой пояс показа; нет — пояс браузера
 */
function shortDate(at: string, i18n: I18n, zone?: string): string {
  const date = new Date(at);
  const year = (moment: Date) => (zone === 'UTC' ? moment.getUTCFullYear() : moment.getFullYear());
  const sameYear = year(date) === year(new Date());
  return date.toLocaleDateString(i18n.locale,
    { day: 'numeric', month: 'short', year: sameYear ? undefined : 'numeric', timeZone: zone });
}

/**
 * Дата в строке списка результатов (§179): у списка нет шапки, поэтому дата подписана — «Posted 14 Oct» (дата
 * размещения работодателем или площадкой, день UTC) или «Found 14 Oct» (даты размещения нигде нет — день первого
 * обнаружения).
 */
export function rowOpenedText(opened: Opened, i18n: I18n): string {
  return i18n.t(opened.byEmployer ? 'list.posted' : 'list.found',
    { date: shortDate(opened.at, i18n, opened.byEmployer ? 'UTC' : undefined) });
}

/** Дата отметки в строке «Not interested» (§180): «Marked 14 Oct»; нет даты — пусто. */
export function rowMarkedText(at: string | null, i18n: I18n): string {
  return at === null ? '' : i18n.t('list.marked', { date: shortDate(at, i18n) });
}

/** Дата события по языку интерфейса; нет даты — пусто. */
export function dateText(at: string | null, i18n: I18n): string {
  return at === null ? '' : new Date(at).toLocaleDateString(i18n.locale);
}

/** Сколько дней после отклика без ответа работодателя, прежде чем отклик напоминает о себе (решение владельца 2026-10-28, §216). */
export const NO_REPLY_DAYS = 21;

/**
 * «No reply for N days» (§216): статус «Applied» поставлен {@link NO_REPLY_DAYS} и более дней назад, вакансия не закрыта;
 * иначе пусто. Собеседование и отказ — ответ уже есть, напоминания нет.
 */
export function noReplyText(status: UserStatus, i18n: I18n, now: number = Date.now()): string {
  if (status.code !== 'APPLIED' || status.closed || status.at === null) {
    return '';
  }
  const days = Math.floor((now - new Date(status.at).getTime()) / 86_400_000);
  return days >= NO_REPLY_DAYS ? i18n.t('list.noReply', { days }) : '';
}

/** Подпись статуса вакансии у пользователя (§156). */
export function statusLabel(code: UserStatus['code'], i18n: I18n): string {
  return i18n.t(('status.' + code) as MessageKey);
}

/** Подпись строки истории (§156): статусы, «Получена», «Возвращена», «Закрыта работодателем». */
export function historyLabel(event: HistoryLine['event'], i18n: I18n): string {
  return i18n.t(('history.' + event) as MessageKey);
}

/** Страны Европейского союза (как регион «EU» в regions.json воркера): такая территория — «from EU» (§158). */
const EU = ['AT', 'BE', 'BG', 'HR', 'CY', 'CZ', 'DK', 'EE', 'FI', 'FR', 'DE', 'GR', 'HU', 'IE', 'IT', 'LV', 'LT', 'LU',
  'MT', 'NL', 'PL', 'PT', 'RO', 'SK', 'SI', 'ES', 'SE'];

/** Подписи формата работы в сведениях (бизнес-описание §6); формат не указан или противоречив — строки нет (§192). */
const FORMAT_KEYS: Record<NonNullable<Work['format']>, MessageKey> = {
  OFFICE: 'vacancy.onlyOffice',
  REMOTE: 'vacancy.onlyRemote',
  HYBRID: 'vacancy.hybrid',
};

/** Формат работы в сведениях: удалённо — с территорией, если она есть («Only remote — from EU», «Только удалённо: ЕС»). */
export function formatText(work: Work, i18n: I18n): string {
  if (work.format === null) {
    return '';
  }
  const format = i18n.t(FORMAT_KEYS[work.format]);
  const territory = work.remoteTerritory ?? [];
  if (territory.length === 0) {
    return format;
  }
  const eu = territory.length === EU.length && EU.every((country) => territory.includes(country));
  return i18n.t('vacancy.from', { format, territory: eu ? i18n.t('vacancy.eu') : countryNames(territory, i18n) });
}

/**
 * Требования к языку: «English — required, German — preferred» (§158); не указаны — строки нет (§192). Названия
 * языков — средствами браузера (Intl.DisplayNames) на языке интерфейса.
 */
export function languagesText(languages: LanguageRequirement[], i18n: I18n): string {
  const names = new Intl.DisplayNames([i18n.locale], { type: 'language' });
  return languages.map((item) => (names.of(item.language) ?? item.language) + ' — '
    + i18n.t(item.required ? 'vacancy.required' : 'vacancy.preferred')).join(', ');
}
