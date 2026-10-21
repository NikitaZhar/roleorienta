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

/** Значение справа в строке: дата открытия, статус с датой или дата отметки «Не интересно». */
const LAST_COLUMN: Record<ListKind, { value: (item: VacancyItem, i18n: I18n) => string }> = {
  results: { value: (item, i18n) => openedText(item.timeline.opened, i18n) },
  responses: { value: (item, i18n) => statusText(item.timeline.status, i18n) },
  notInterested: { value: (item, i18n) => dateText(item.timeline.status.at, i18n) },
};

/** Пустой раздел «Мои отклики» и «Не интересно» (§147). */
const EMPTY_TEXT: Record<Exclude<ListKind, 'results'>, MessageKey> = {
  responses: 'list.emptyResponses',
  notInterested: 'list.emptyNotInterested',
};

/** Пустой накопленный список перечитывается: первая порция новой версии условий приходит в течение ~30 с (§120). */
const REFRESH_MS = 5000;
const REFRESH_TIMES = 24;
/** Ширины строк-заготовок, пока идёт сбор (% ширины): серые полосы вместо пустоты (§171). */
const SKELETON_WIDTHS = [62, 48, 70, 55, 44, 66];


/**
 * Список вакансий страницами по номеру (§151) строками в две строчки (новый облик, §171): позиция; под ней
 * «работодатель · страна»; справа — дата или статус (агентство — только в сведениях). Результаты поиска (открытые позже
 * сверху, дата открытия — §127; над списком — «N vacancies · M new · Updated hourly», у новых — пилюля «New»,
 * просмотренные — светлее, §156), «Мои отклики»
 * (последняя колонка — статус и дата, у закрытой — «closed») или «Не интересно» (дата отметки и «Return» в строке:
 * вакансия возвращается к прежнему статусу, §156). Внизу — номера страниц и выбор размера страницы; выбор
 * запоминается в браузере ({@code localStorage}: хранилище браузера, переживает перезагрузку; недоступно —
 * размер по умолчанию). Условий нет — список не показывается (выше — «Set up your search»); задано, а вакансий нет —
 * «пока нет». Сразу после новых условий ({@code poll}) пустая первая страница перечитывается каждые 5 с в течение
 * 2 минут, на экране — «идёт сбор» и строки-заготовки; первая порция выдаётся в течение ~30 с (§120); следующий
 * запрос — только после ответа на предыдущий (аудит §121). Перечитывание ({@code reload}, «Return») показывает ту же
 * страницу; если она опустела (ушла последняя вакансия последней страницы) — последнюю непустую. Ошибка загрузки — сообщение с «Retry»; размер
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
  if (results && data.total === 0 && !hasConditions) {
    return null;
  }
  const collecting = results && data.total === 0 && poll && refreshes < REFRESH_TIMES;
  return (
    <section className="list">
      {results ? data.total > 0 && (
        <div className="list-head">
          <h1>{data.total === 1 ? t('list.countOne') : t('list.count', { total: data.total })}</h1>
          {data.newCount > 0 && <span className="fresh">{t('list.newCount', { fresh: data.newCount })}</span>}
          <span className="muted">· {t('list.updatedHourly')}</span>
        </div>
      ) : title !== undefined && <h1>{title}</h1>}
      {data.total === 0 && kind !== 'results' && <Empty title={t(EMPTY_TEXT[kind])} />}
      {collecting && (
        <div className="collecting" role="status">
          <p className="muted">{t('list.collecting')}</p>
          {SKELETON_WIDTHS.map((width, index) => (
            <div key={index} className="skeleton-row" aria-hidden="true">
              <span className="skeleton" style={{ width: width + '%' }} />
              <span className="skeleton short" style={{ width: (width / 2) + '%' }} />
            </div>
          ))}
        </div>
      )}
      {results && data.total === 0 && !collecting && (
        <Empty title={t('list.nothingYetTitle')} text={t('list.nothingYetText')} />
      )}
      {data.total > 0 && (
        <ul className="rows">
          {data.items.map((item) => {
            const fresh = results && item.timeline.status.code === 'NEW';
            return (
              <li key={item.id} className={actions ? 'actionable' : undefined}>
                <button type="button" className={results && !fresh ? 'row seen' : 'row'} onClick={() => onOpen(item.id)}>
                  <span className="row-main">
                    <span className="row-title">
                      {fresh && <span className="pill pill-fresh">{t('list.new')}</span>}
                      <span className="position">{item.position}</span>
                    </span>
                    <span className="row-meta">
                      {(item.parties.employer ?? t('list.noEmployer')) + ' · ' + rowCountryText(item.work, i18n)}
                    </span>
                  </span>
                  <span className="row-side">{column.value(item, i18n)}</span>
                </button>
                {actions && (
                  <button type="button" className="link row-action" disabled={loading} onClick={() => giveBack(item.id)}>
                    {t('list.return')}
                  </button>
                )}
              </li>
            );
          })}
        </ul>
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

/** Пустой раздел — одинаково во всех разделах (§152): строка-заголовок и, если есть, пояснение; без рамки (§171). */
export function Empty({ title, text }: { title: string; text?: string }) {
  return (
    <div className="empty">
      <p className="lead">{title}</p>
      {text !== undefined && <p className="muted">{text}</p>}
    </div>
  );
}
