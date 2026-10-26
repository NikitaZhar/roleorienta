import { type ReactNode, useEffect, useRef, useState } from 'react';
import { searchCondition, setStatus, type VacancyItem, type VacancyPage } from './api';
import { dateText, rowMarkedText, rowMetaText, rowOpenedText, statusLabel } from './format';
import { errorText, type I18n, type MessageKey, useI18n } from './i18n';
import { PAGE_SIZES, PageSize, Pager, storedSize, storeSize } from './Pager';

/**
 * Какой список (§156): результаты поиска, «Мои отклики» или «Не интересно». У результатов — «N vacancies · M not
 * opened», плашка «Not opened», точка «после прошлого захода», крестик «Not interested», объяснение пустого списка по
 * условиям; у остальных — свой пустой текст и своя последняя колонка.
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
  results: (item, i18n) => (
    <span className="row-date">
      {item.timeline.sinceLastVisit && (
        <span className="dot" role="img" aria-label={i18n.t('list.sinceLastVisit')} title={i18n.t('list.sinceLastVisit')} />
      )}
      {rowOpenedText(item.timeline.opened, i18n)}
    </span>
  ),
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
/** Сколько ждёт «Undo» после «✓» или «×» (§191, §193): отметка уходит на сервер только после этого. */
const UNDO_MS = 5000;

/** Отметки из строки результатов (§193): «✓» — откликнулся, «×» — не интересна; подпись полосы «Undo». */
type RowMark = 'APPLIED' | 'NOT_INTERESTED';
const MARK_MOVED: Record<RowMark, MessageKey> = {
  APPLIED: 'list.movedToApplications',
  NOT_INTERESTED: 'list.dismissed',
};

/**
 * Список вакансий страницами по номеру (§151) строками в две строчки (новый облик, §171): позиция (у известного
 * формата — метка Office / Remote, гибрид без метки, §192); под ней «работодатель · страна» (чего нет — не
 * показывается, §192); справа — дата или статус (агентство — только в сведениях). Результаты (§193): строка — ссылка на
 * первичную публикацию в новой вкладке («↗» у позиции — подсказка), открытие снимает «Not opened» («Viewed»); в строке
 * «✓» — откликнулся (CV sent, уходит в «Мои отклики») и «×» — не интересна; сведения из результатов не открываются.
 * «Мои отклики» и «Не интересно»: строка открывает сведения (статус и история), «↗» рядом — публикация. Результаты поиска (открытые позже
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
 * страницы во время загрузки не меняется (аудит §154). В результатах (§191, §193): «✓» или «×» — строка сразу
 * скрывается, на {@value UNDO_MS} мс внизу «Moved to My applications · Undo» или «Moved to Not interested · Undo»;
 * отметка уходит на сервер только по истечении срока, поэтому «Undo» ничего не записывает (ни статуса, ни строк
 * истории). Новая отметка, уход со страницы или из списка — ожидающая уходит сразу. Закрытая вкладка браузера в эти
 * секунды отметку теряет.
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
  /** Вакансии, скрытые крестиком до ответа сервера (§191). */
  const [dismissed, setDismissed] = useState<number[]>([]);
  /** Отметка, ждущая «Undo» (для подписи полосы); ref — вакансия, отметка и таймер, доступны и при уходе из списка. */
  const [waiting, setWaiting] = useState<RowMark | null>(null);
  const pending = useRef<{ id: number; mark: RowMark; timer: number } | null>(null);

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

  /** Отметка из строки уходит на сервер; затем страница перечитывается (вакансия уходит из результатов). */
  async function commit(id: number, mark: RowMark) {
    if (pending.current?.id === id) {
      window.clearTimeout(pending.current.timer);
      pending.current = null;
      setWaiting(null);
    }
    try {
      await setStatus(id, mark);
    } catch (failure) {
      setDismissed((ids) => ids.filter((other) => other !== id));
      setMessage(errorText(failure, t));
      return;
    }
    await show(data?.page ?? 1, size);
    setDismissed((ids) => ids.filter((other) => other !== id));
  }

  /** «✓» или «×» в строке: прежняя ожидающая отметка уходит сразу, новая ждёт «Undo». */
  function markRow(id: number, mark: RowMark) {
    if (pending.current !== null) {
      void commit(pending.current.id, pending.current.mark);
    }
    setDismissed((ids) => [...ids, id]);
    pending.current = { id, mark, timer: window.setTimeout(() => void commit(id, mark), UNDO_MS) };
    setWaiting(mark);
  }

  /**
   * Открытие публикации из строки результатов (§193): у не открытой — «Viewed» на сервере и сразу на экране (пилюля
   * «Not opened» снимается, число не открытых уменьшается); ошибка записи — сообщение, публикация всё равно открыта.
   */
  function opened(item: VacancyItem) {
    if (item.timeline.status.code !== 'NEW') {
      return;
    }
    const viewed = 'VIEWED' as const;
    setData((current) => current === null ? null : {
      ...current,
      newCount: Math.max(0, current.newCount - 1),
      items: current.items.map((other) => other.id !== item.id ? other
        : { ...other, timeline: { ...other.timeline, status: { ...other.timeline.status, code: viewed } } }),
    });
    setStatus(item.id, viewed).catch((failure: unknown) => setMessage(errorText(failure, t)));
  }

  /** «Undo»: на сервер ничего не уходит, строка возвращается. */
  function undo() {
    const current = pending.current;
    if (current === null) {
      return;
    }
    window.clearTimeout(current.timer);
    pending.current = null;
    setWaiting(null);
    setDismissed((ids) => ids.filter((other) => other !== current.id));
  }

  /** Уход из списка (другой раздел, выход): ожидающая отметка уходит сразу. */
  useEffect(() => () => {
    const current = pending.current;
    if (current !== null) {
      window.clearTimeout(current.timer);
      void setStatus(current.id, current.mark);
    }
  }, []);

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
          {data.items.filter((item) => !dismissed.includes(item.id)).map((item) => {
            const fresh = results && item.timeline.status.code === 'NEW';
            const seen = (results && !fresh) || actions;
            return (
              <li key={item.id} className="actionable">
                {results ? (
                  <a className={seen ? 'row seen' : 'row'} href={item.posting.url} target="_blank"
                    rel="noopener noreferrer" title={t('vacancy.openPosting')} onClick={() => opened(item)}>
                    <RowContent item={item} fresh={fresh} side={side(item, i18n)} link />
                  </a>
                ) : (
                  <button type="button" className={seen ? 'row seen' : 'row'} onClick={() => onOpen(item.id)}>
                    <RowContent item={item} fresh={fresh} side={side(item, i18n)} />
                  </button>
                )}
                {!results && (
                  <a className="row-open" href={item.posting.url} target="_blank" rel="noopener noreferrer"
                    aria-label={t('vacancy.openPosting')} title={t('vacancy.openPosting')}>
                    ↗
                  </a>
                )}
                {actions && (
                  <button type="button" className="link row-action" disabled={loading} onClick={() => giveBack(item.id)}>
                    {t('list.return')}
                  </button>
                )}
                {results && (
                  <>
                    <button type="button" className="row-mark" aria-label={t('list.applied')} title={t('list.applied')}
                      onClick={() => markRow(item.id, 'APPLIED')}>
                      ✓
                    </button>
                    <button type="button" className="row-mark" aria-label={t('list.dismiss')} title={t('list.dismiss')}
                      onClick={() => markRow(item.id, 'NOT_INTERESTED')}>
                      ×
                    </button>
                  </>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {waiting !== null && (
        <div className="undo-bar" role="status">
          <span>{t(MARK_MOVED[waiting])}</span>
          <button type="button" className="link" onClick={undo}>{t('list.undo')}</button>
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

/**
 * Содержимое строки (§171): позиция с пометками, «работодатель · страна», справа — дата или статус. {@code link} —
 * строка ведёт в публикацию (§193): у позиции «↗».
 */
function RowContent({ item, fresh, side, link = false }: { item: VacancyItem; fresh: boolean; side: ReactNode;
  link?: boolean }) {
  const i18n = useI18n();
  const { t } = i18n;
  return (
    <>
      <span className="row-main">
        <span className="row-title">
          {fresh && <span className="pill pill-fresh">{t('list.new')}</span>}
          <span className="position">{item.posting.position}</span>
          {link && <span className="row-link" aria-hidden="true">↗</span>}
          {item.work.format === 'OFFICE' && <span className="pill pill-muted">{t('format.office')}</span>}
          {item.work.format === 'REMOTE' && <span className="pill pill-muted">{t('format.remote')}</span>}
          {item.timeline.status.closed && <span className="pill pill-muted">{t('vacancy.closed')}</span>}
        </span>
        <span className="row-meta">{rowMetaText(item.parties.employer, item.work, i18n)}</span>
      </span>
      <span className="row-side">{side}</span>
    </>
  );
}

/** Пустой раздел — одинаково во всех разделах (§152): строка-заголовок и, если есть, пояснение; без рамки (§171). */
function Empty({ title, text }: { title: string; text?: string }) {
  return (
    <div className="empty">
      <p className="lead">{title}</p>
      {text !== undefined && <p className="muted">{text}</p>}
    </div>
  );
}
