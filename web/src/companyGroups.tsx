import type { CompanyItem } from './api';
import { type MessageKey, useI18n } from './i18n';

/**
 * Группа компании меткой (бизнес-описание §4.2; §138, §143): подпись и цвет по группе (цвет только дополняет текст).
 * Подпись — на языке интерфейса (§162); на экране администратора — по-английски.
 */
export function GroupBadge({ item }: { item: CompanyItem }) {
  const { t } = useI18n();
  return (
    <span className={'badge badge-' + item.check.group.toLowerCase()}>
      {t(('group.' + item.check.group) as MessageKey)}
    </span>
  );
}
