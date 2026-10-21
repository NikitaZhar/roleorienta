import type { CompanyItem } from './api';
import { type MessageKey, useI18n } from './i18n';

/** Группы в порядке расшифровки. */
const GROUPS = ['YES', 'PORTAL', 'MAYBE', 'NO', 'NOT_EMPLOYER'];

/**
 * Группа компании короткой меткой в одну строку (бизнес-описание §4.2; §138, §143; решение владельца 2026-10-21,
 * §163): одно-два слова и цвет по группе (цвет только дополняет текст); полная расшифровка — {@link GroupLegend} под
 * таблицей. Причина, по которой кадровая страница не найдена, — подсказкой при наведении ({@code reason}), а не
 * второй строкой: она повторяла метку. Подпись — на языке интерфейса (§162); на экране администратора — по-английски.
 */
export function GroupBadge({ item, reason }: { item: CompanyItem; reason?: string }) {
  const { t } = useI18n();
  return (
    <span className={'badge badge-' + item.check.group.toLowerCase()} title={reason}>
      {t(('group.' + item.check.group) as MessageKey)}
    </span>
  );
}

/** Расшифровка меток групп под таблицей компаний: только те группы, что есть в таблице. */
export function GroupLegend({ items }: { items: CompanyItem[] }) {
  const { t } = useI18n();
  const shown = GROUPS.filter((group) => items.some((item) => item.check.group === group));
  if (shown.length === 0) {
    return null;
  }
  return (
    <dl className="legend" aria-label={t('legend.title')}>
      {shown.map((group) => (
        <div key={group}>
          <dt><span className={'badge badge-' + group.toLowerCase()}>{t(('group.' + group) as MessageKey)}</span></dt>
          <dd>{t(('legend.' + group) as MessageKey)}</dd>
        </div>
      ))}
      {items.some((item) => item.check.reason !== null) && <p className="muted">{t('legend.reason')}</p>}
    </dl>
  );
}
