import type { HistoryLine, Opened, UserStatus, Work } from './api';

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

/** Подписи статусов вакансии у пользователя (§156); тексты EN / RU — срез 7. */
export const STATUS_LABELS: Record<UserStatus['code'], string> = {
  NEW: 'New',
  VIEWED: 'Viewed',
  NOT_INTERESTED: 'Not interested',
  APPLIED: 'CV sent',
  INTERVIEW: 'Interview',
  REJECTED: 'Rejected',
};

/** Подписи строк истории (§156): статусы, «Получена», «Возвращена», «Закрыта работодателем». */
export const HISTORY_LABELS: Record<HistoryLine['event'], string> = {
  RECEIVED: 'Received',
  VIEWED: 'Viewed',
  NOT_INTERESTED: 'Not interested',
  APPLIED: 'CV sent',
  INTERVIEW: 'Interview',
  REJECTED: 'Rejected',
  RETURNED: 'Returned',
  CLOSED: 'Closed by employer',
};

/** Дата события в локали браузера; нет даты — пусто. */
export function dateText(at: string | null): string {
  return at === null ? '' : new Date(at).toLocaleDateString();
}

/** Статус в строке «Мои отклики»: «статус · дата», у закрытой работодателем — «· closed» (§147). */
export function statusText(status: UserStatus): string {
  return [STATUS_LABELS[status.code], dateText(status.at), status.closed ? 'closed' : '']
    .filter((part) => part !== '').join(' · ');
}
