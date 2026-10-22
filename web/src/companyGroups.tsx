import type { CompanyItem } from './api';
import { type MessageKey, useI18n } from './i18n';

/**
 * Группа компании короткой меткой в одну строку (бизнес-описание §4.2; §138, §143, §163): одно-три слова и цвет по
 * группе (цвет только дополняет текст). Подписи понятны без расшифровки (решение владельца 2026-10-22, §176:
 * расшифровка под таблицей и подсказка при наведении убраны); причина, по которой страница вакансий не найдена, —
 * в строке таблицы. Подпись — на языке интерфейса (§162); на экране администратора — по-английски.
 */
export function GroupBadge({ item }: { item: CompanyItem }) {
  const { t } = useI18n();
  return (
    <span className={'badge badge-' + item.check.group.toLowerCase()}>
      {t(('group.' + item.check.group) as MessageKey)}
    </span>
  );
}
