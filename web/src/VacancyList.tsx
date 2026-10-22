import { type ReactNode, useEffect, useState } from 'react';
import { searchCondition, setStatus, type VacancyItem, type VacancyPage } from './api';
import { dateText, rowCountryText, rowMarkedText, rowOpenedText, statusLabel } from './format';
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

/**
 * Значение справа в строке: дата открытия с подписью «Posted» / «Found» (§179); в «Мои отклики» — статус пилюлей своего цвета и под ней дата (срез 4
 * нового облика); в «Не интересно» — дата отметки с подписью «Marked» (§180).
 */
const LAST_COLUMN: Record<ListKind, (item: VacancyItem, i18n: I18n) => ReactNode> = {
  results: (item, i18n) => rowOpenedText(item.timeline.opened, i18n),
  responses: (item, i18n) => (
    <>
      <span className={'pill status-' + item.timeline.status.code}>{statusLabel(item.timeline.status.code, i18n)}</span>
      <span>{dateText(item.timeline.status.at, i18n)}</span>
    </>
  ),
  notInterested: (item, i18n) => rowMarkedText(item.timeline.status.at, i18n),
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
 * просмотренные — светлее, §156). «Мои отклики» и «Не интересно» (срез 4 нового облика): заголовок раздела и рядом
 * число вакансий; в «Мои отклики» справа — статус пилюлей и дата, у закрытой работодателем — пилюля «closed» рядом с
 * позицией; в «Не интересно» строки светлее, справа — дата отметки и «Return»: вакансия возвращается к прежнему
 * статусу (§156). Внизу — номера страниц и выбор размера страницы; выбор
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
  const side = LAST_COLUMN[kind];
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
      ) : title !== undefined && (
        <div className="list-head">
          <h1>{title}</h1>
          {data.total > 0 && (
            <span className="muted">· {data.total === 1 ? t('list.countOne') : t('list.count', { total: data.total })}</span>
          )}
        </div>
      )}
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
            const seen = (results && !fresh) || actions;
            return (
              <li key={item.id} className={actions ? 'actionable' : undefined}>
                <button type="button" className={seen ? 'row seen' : 'row'} onClick={() => onOpen(item.id)}>
                  <span className="row-main">
                    <span className="row-title">
                      {fresh && <span className="pill pill-fresh">{t('list.new')}</span>}
                      <span className="position">{item.position}</span>
                      {item.timeline.status.closed && <span className="pill pill-muted">{t('vacancy.closed')}</span>}
                    </span>
                    <span className="row-meta">
                      {(item.parties.employer ?? t('list.noEmployer')) + ' · ' + rowCountryText(item.work, i18n)}
                    </span>
                  </span>
                  <span className="row-side">{side(item, i18n)}</span>
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
