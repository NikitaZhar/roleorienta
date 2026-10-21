import { useEffect, useState } from 'react';
import { searchCondition, setStatus, type VacancyItem, type VacancyPage } from './api';
import { dateText, openedText, rowCountryText, statusText } from './format';
import { errorText, type I18n, type MessageKey, useI18n } from './i18n';
import { PAGE_SIZES, PageSize, Pager, storedSize, storeSize } from './Pager';

/**
 * Какой список (§156): результаты поиска, «Мои отклики» или «Не интересно». У результатов — «Found N · new M»,
 * плашка «New», объяснение пустого списка по условиям; у остальных — свой пустой текст и своя последняя колонка.
 */
export type ListKind = 'results' | 'responses' | 'notInterested';

interface Props {
  /** Заголовок раздела; нет — без заголовка (результаты под условиями поиска). */
  title?: string;
  kind: ListKind;
  load: (page: number, size: number) => Promise<VacancyPage>;
  /** Список открыт сразу после новых условий: пустой перечитывается, пока не придёт первая порция. */
  poll: boolean;
  /** Счётчик перечитываний: изменился — перечитать текущую страницу (статус вакансии изменился в сведениях). */
  reload: number;
  onOpen: (id: number) => void;
}

/** Последняя колонка списка: заголовок и значение строки. */
const LAST_COLUMN: Record<ListKind, { head: MessageKey; value: (item: VacancyItem, i18n: I18n) => string }> = {
  results: { head: 'list.opened', value: (item, i18n) => openedText(item.timeline.opened, i18n) },
  responses: { head: 'list.status', value: (item, i18n) => statusText(item.timeline.status, i18n) },
  notInterested: { head: 'list.marked', value: (item, i18n) => dateText(item.timeline.status.at, i18n) },
};

/** Пустой раздел «Мои отклики» и «Не интересно» (§147). */
const EMPTY_TEXT: Record<Exclude<ListKind, 'results'>, MessageKey> = {
  responses: 'list.emptyResponses',
  notInterested: 'list.emptyNotInterested',
};

/** Пустой накопленный список перечитывается: первая порция новой версии условий приходит в течение ~30 с (§120). */
const REFRESH_MS = 5000;
const REFRESH_TIMES = 24;


/**
 * Список вакансий страницами по номеру (§151) строками-колонками «Company · Position · Country · …» (агентство —
 * только в сведениях; на телефоне строка — плитка в две строчки, §147, §152): результаты поиска (открытые позже
 * сверху, дата открытия — §127; над списком — «Found N · new M», у новых — плашка «New», §156), «Мои отклики»
 * (последняя колонка — статус и дата, у закрытой — «closed») или «Не интересно» (дата отметки и «Return» в строке:
 * вакансия возвращается к прежнему статусу, §156). Внизу — номера страниц и выбор размера страницы; выбор
 * запоминается в браузере ({@code localStorage}: хранилище браузера, переживает перезагрузку; недоступно —
 * размер по умолчанию). Пустой накопленный список объясняется: что искать не задано — задать выше; задано —
 * подходящих вакансий пока нет. Сразу после новых условий ({@code poll}) пустая первая страница перечитывается каждые
 * 5 с в течение 2 минут — первая порция выдаётся в течение ~30 с (§120); следующий запрос — только после ответа на
 * предыдущий (аудит §121). Перечитывание ({@code reload}, «Return») показывает ту же страницу; если она опустела
 * (ушла последняя вакансия последней страницы) — последнюю непустую. Ошибка загрузки — сообщение с «Retry»; размер
 * страницы во время загрузки не меняется (аудит §154).
 */
export function VacancyList({ title, kind, load, poll, reload, onOpen }: Props) {
  const i18n = useI18n();
  const { t } = i18n;
  const [data, setData] = useState<VacancyPage | null>(null);
  const [size, setSize] = useState(storedSize);
  const [hasConditions, setHasConditions] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshes, setRefreshes] = useState(0);

  async function show(page: number, rows: number) {
    setLoading(true);
    setMessage(null);
    try {
      let next = await load(page, rows);
      if (next.items.length === 0 && next.total > 0 && page > 1) {
        next = await load(Math.ceil(next.total / rows), rows);
      }
      setData(next);
      if (next.total === 0 && kind === 'results') {
        setHasConditions((await searchCondition()) !== null);
      }
    } catch (failure) {
      setMessage(errorText(failure, t));
    } finally {
      setLoading(false);
    }
  }

  /** «Return» в строке «Не интересно»: вакансия уходит к прежнему статусу, страница перечитывается. */
  async function giveBack(id: number) {
    try {
      await setStatus(id, 'RETURNED');
    } catch (failure) {
      setMessage(errorText(failure, t));
      return;
    }
    show(data?.page ?? 1, size);
  }

  function resize(rows: number) {
    setSize(rows);
    storeSize(rows);
    show(1, rows);
  }

  useEffect(() => {
    show(data?.page ?? 1, size);
  }, [reload]);

  useEffect(() => {
    if (!poll || data === null || loading || !hasConditions || data.total > 0 || refreshes >= REFRESH_TIMES) {
      return;
    }
    const timer = setTimeout(() => {
      setRefreshes((count) => count + 1);
      show(1, size);
    }, REFRESH_MS);
    return () => clearTimeout(timer);
  }, [poll, data, loading, hasConditions, refreshes]);

  if (message !== null) {
    return (
      <p className="error" role="alert">
        {message} <button type="button" onClick={() => show(data?.page ?? 1, size)}>{t('list.retry')}</button>
      </p>
    );
  }
  if (data === null) {
    return <p>{t('app.loading')}</p>;
  }
  const results = kind === 'results';
  const actions = kind === 'notInterested';
  const column = LAST_COLUMN[kind];
  return (
    <section>
      {title !== undefined && <h2>{title}</h2>}
      {results && data.total > 0 && <p className="count">{t('list.found', { total: data.total, fresh: data.newCount })}</p>}
      {data.total === 0 && kind !== 'results' && <Empty text={t(EMPTY_TEXT[kind])} />}
      {data.total === 0 && results && !hasConditions && <Empty text={t('list.noResults')} />}
      {data.total === 0 && results && hasConditions && (
        <Empty text={poll && refreshes < REFRESH_TIMES ? t('list.searching') : t('list.nothingYet')} />
      )}
      {data.total > 0 && (
        <div className="rows">
          <div className={actions ? 'actionable' : undefined} aria-hidden="true">
            <div className="row head">
              <span>{t('list.company')}</span><span>{t('list.position')}</span><span>{t('list.country')}</span>
              <span>{t(column.head)}</span>
            </div>
            {actions && <span className="row-action placeholder">{t('list.return')}</span>}
          </div>
          <ul>
            {data.items.map((item) => (
              <li key={item.id} className={actions ? 'actionable' : undefined}>
                <button type="button" className="row" onClick={() => onOpen(item.id)}>
                  <span className="company">{item.parties.employer ?? t('list.noEmployer')}</span>
                  <span className="position">
                    {results && item.timeline.status.code === 'NEW' && <span className="badge">{t('list.new')}</span>}
                    {item.position}
                  </span>
                  <span className="country">{rowCountryText(item.work, i18n)}</span>
                  <span className="last">{column.value(item, i18n)}</span>
                </button>
                {actions && (
                  <button type="button" className="row-action" disabled={loading} onClick={() => giveBack(item.id)}>
                    {t('list.return')}
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {data.total > 0 && (
        <div className="list-footer">
          <Pager page={data.page} pages={Math.ceil(data.total / data.size)} busy={loading}
            onPage={(page) => show(page, size)} />
          <PageSize size={size} sizes={PAGE_SIZES} busy={loading} onSize={resize} />
        </div>
      )}
    </section>
  );
}

/** Пустой раздел — одинаково во всех разделах (§152). */
export function Empty({ text }: { text: string }) {
  return <div className="empty"><p className="lead">{text}</p></div>;
}
