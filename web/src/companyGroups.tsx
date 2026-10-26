import type { CompanyItem } from './api';
import { type MessageKey, useI18n } from './i18n';

/**
 * Группа компании меткой в одну строку (бизнес-описание §4.2; §138, §143, §163) и цвет по группе (цвет только
 * дополняет текст). Подпись — ответ на вопрос пользователя «собираются ли вакансии этой компании» (§200, аудит §197):
 * «Collected from the company site» / «… state portal» / «Not collected»; причина, по которой не собираются, — в строке
 * таблицы под меткой. Подпись — на языке интерфейса (§162); на экране администратора — по-английски.
 */
export function GroupBadge({ item }: { item: CompanyItem }) {
  const { t } = useI18n();
  return (
    <span className={'badge badge-' + item.check.group.toLowerCase()}>
      {t(('group.' + item.check.group) as MessageKey)}
    </span>
  );
}
