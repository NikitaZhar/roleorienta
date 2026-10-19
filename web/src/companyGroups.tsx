import type { CompanyItem } from './api';

/** Подписи групп компании (бизнес-описание §4.2; §138, §143): экран компаний и экран администратора. */
export const GROUPS: Record<string, string> = {
  YES: 'Career page read',
  PORTAL: 'State portal vacancies only',
  MAYBE: 'Site found, no readable career page',
  NO: 'No site found',
  NOT_EMPLOYER: 'Not an employer',
};

/** Группа компании меткой: подпись и цвет по группе (цвет только дополняет текст). */
export function GroupBadge({ item }: { item: CompanyItem }) {
  return <span className={'badge badge-' + item.check.group.toLowerCase()}>{GROUPS[item.check.group]}</span>;
}
