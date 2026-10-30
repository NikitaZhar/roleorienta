import { type ReactNode, useEffect, useRef, useState } from 'react';
import {
  type PositionCount, type ResultFilter, resultFilters, searchCondition, setStatus, type VacancyItem, type VacancyPage,
} from './api';
import { dateText, noReplyText, rowMarkedText, rowMetaText, rowOpenedText, statusLabel } from './format';
import { errorText, type I18n, type MessageKey, useI18n } from './i18n';
import { PAGE_SIZES, PageSize, Pager, storedSize, storeSize } from './Pager';

/**
 * Какой список (§156, §196, §217): результаты поиска, действующие «Мои отклики», под ними свёрнуто — «без ответа 3+
 * недели» и завершённые («Employer declined», закрытые и без ответа 6+ недель), или «Не интересно». У каждого — своя
 * последняя колонка; у результатов — свой заголовок и объяснение пустого списка по условиям, у «Моих откликов» и «Не
 * интересно» — свой пустой текст, у свёрнутых пустого вида нет — пустые не показываются.
 */
export type ListKind = 'results' | 'responses' | 'silent' | 'finished' | 'notInterested';

interface Props {
  /** Заголовок раздела; нет — без заголовка (результаты под условиями поиска). */
  title?: string;
  kind: ListKind;
  /**
   * Страница списка; {@code filter} — отбор внутри результатов (§194, §200; только у результатов); {@code query} —
   * поиск (§226; у «Моих откликов» и «Не интересно»).
   */
  load: (page: number, size: number, filter?: ResultFilter, query?: string) => Promise<VacancyPage>;
  /** Список открыт сразу после новых условий: пустой перечитывается, пока не придёт первая порция. */
  poll: boolean;
  /** Счётчик перечитываний: изменился — перечитать текущую страницу (статус вакансии изменился в сведениях). */
  reload: number;
  onOpen: (id: number) => void;
  /** Поиск по позиции, работодателю и агентству (§226); пусто — без поиска. */
  query?: string;
  /** Есть — над списком поле поиска; вызывается с запросом через {@value SEARCH_DELAY_MS} мс после ввода. */
  onQuery?: (query: string) => void;
}

/** Статус отклика пилюлей своего цвета, под ней дата и, если ответа давно нет, «No reply for N days» (§216). */
function responseColumn(item: VacancyItem, i18n: I18n): ReactNode {
  return (
    <>
      <span className={'pill status-' + item.timeline.status.code}>{statusLabel(item.timeline.status.code, i18n)}</span>
      <span>{dateText(item.timeline.status.at, i18n)}</span>
      {noReplyText(item.timeline.status, i18n) !== '' && (
        <span className="no-reply">{noReplyText(item.timeline.status, i18n)}</span>
      )}
    </>
  );
}

/**
 * Значение справа в строке: в результатах — точка «пришла после прошлого захода» (§191) и дата открытия с подписью
 * «Posted» / «Found» (§179); в «Моих откликах» и завершённых — статус и дата; в «Не интересно» — дата отметки с
 * подписью «Marked» (§180).
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
  responses: responseColumn,
  silent: responseColumn,
  finished: responseColumn,
  notInterested: (item, i18n) => rowMarkedText(item.timeline.status.at, i18n),
};

/** Пустой раздел «Мои отклики» и «Не интересно» (§147). */
const EMPTY_TEXT: Record<Exclude<ListKind, 'results' | 'silent' | 'finished'>, MessageKey> = {
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
/** Поиск (§226) уходит на сервер через столько мс после последней буквы — не на каждую букву. */
const SEARCH_DELAY_MS = 300;
/** Длиннее запрос не нужен: название и работодатель короче; сервер обрезает так же ({@code VacancyService.MAX_QUERY}). */
const SEARCH_MAX = 100;

/** Отправка ожидающих «Undo» отметок каждого открытого списка (см. {@link flushMarks}). */
const flushers = new Set<() => Promise<void>>();

/**
 * Ожидающие «Undo» отметки уходят на сервер сейчас — перед выходом из учётной записи (аудит §197): после выхода сессии
 * нет, и отметка, отправленная при уходе из списка, пропала бы. Не отправилась — выход всё равно выполняется (ошибка
 * отметки не выдаётся за ошибку выхода).
 */
export async function flushMarks(): Promise<void> {
  await Promise.allSettled([...flushers].map((flush) => flush()));
}

/** Отметки из строки результатов (§193): «✓» — откликнулся, «×» — не интересна; подпись полосы «Undo». */
type RowMark = 'APPLIED' | 'NOT_INTERESTED';
const MARK_MOVED: Record<RowMark, MessageKey> = {
  APPLIED: 'list.movedToApplications',
  NOT_INTERESTED: 'list.dismissed',
};

/**
 * Список вакансий страницами по номеру (§151) строками в две строчки (новый облик, §171): позиция (у известного
 * формата — метка Office / Remote, гибрид без метки; у закрытой работодателем — «closed»); под ней «работодатель ·
 * страна» (чего нет — не показывается, §192); справа — значение {@link LAST_COLUMN}. Внизу — номера страниц и выбор
 * размера страницы; выбор запоминается в браузере ({@code localStorage}: хранилище браузера, переживает перезагрузку;
 * недоступно — размер по умолчанию). Ошибка загрузки — сообщение с «Retry»; размер страницы во время загрузки не
 * меняется (аудит §154). Перечитывание ({@code reload}) показывает ту же страницу; опустела — последнюю непустую.
 *
 * <p>Результаты поиска: открытые позже сверху (дата открытия — §127); над списком «N vacancies · M not opened · Updated
 * hourly», у не открытых — пилюля «Not opened», открытые — светлее (§156, §191). Строка — ссылка на первичную
 * публикацию в новой вкладке («↗» у позиции), открытие ставит «Viewed» (§193); в строке «✓» — откликнулся (Applied,
 * уходит в «Мои отклики») и «×» — не интересна: строка сразу скрывается, на {@value UNDO_MS} мс внизу «Moved to … ·
 * Undo»; отметка уходит на сервер только по истечении срока, поэтому «Undo» ничего не записывает (ни статуса, ни строк
 * истории); новая отметка, уход из списка или выход — ожидающая уходит сразу ({@link flushMarks}); закрытая вкладка
 * браузера в эти секунды отметку теряет. Несколько позиций в условиях (§194): над списком метки позиций с числом
 * вакансий, нажатие — только эта позиция с первой страницы, повторное — снова все; в строке — к каким позициям
 * подошла. Условий нет — список не показывается (выше — «Set up your search»); заданы, а вакансий нет — «пока нет»;
 * сразу после новых условий ({@code poll}) пустая первая страница перечитывается каждые 5 с в течение 2 минут, на
 * экране — «идёт сбор» и строки-заготовки (первая порция — в течение ~30 с, §120; следующий запрос — только после ответа
 * на предыдущий, аудит §121).
 *
 * <p>«Мои отклики» (действующие), завершённые и «Не интересно»: строка открывает сведения (статус и история),
 * «↗» рядом — публикация. В «Моих откликах» — заголовок с числом вакансий; под ними (§196)
 * свёрнутыми строками «No reply 3+ weeks · N» — «Applied» без ответа 3–6 недель (§217) — и «Completed · N» — «Employer
 * declined», отклики по закрытым работодателем (§213) и без ответа 6+ недель (§217), раскрытие завершённых — строками светлее; в «Не интересно» строки светлее, справа —
 * дата отметки и «Return»: вакансия возвращается к прежнему статусу (§156).
 *
 * <p>Поиск (§226): у «Моих откликов» и «Не интересно» под заголовком поле «по работодателю или позиции» — запрос уходит
 * через {@value SEARCH_DELAY_MS} мс после ввода, список — с первой страницы; в «Моих откликах» найденные во всех
 * группах идут одним списком (свёрнутые группы на это время скрывает App); «×» в поле — снова весь список.
 */
export function VacancyList({ title, kind, load, poll, reload, onOpen, query = '', onQuery }: Props) {
  const i18n = useI18n();
  const { t } = i18n;
  const [data, setData] = useState<VacancyPage | null>(null);
  const [size, setSize] = useState(storedSize);
  const [hasConditions, setHasConditions] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshes, setRefreshes] = useState(0);
  /** Вакансии, скрытые «✓» или «×» до ответа сервера (§191, §193). */
  const [dismissed, setDismissed] = useState<number[]>([]);
  /** Отметка, ждущая «Undo» (для подписи полосы); ref — вакансия, отметка и таймер, доступны и при уходе из списка. */
  const [waiting, setWaiting] = useState<RowMark | null>(null);
  const pending = useRef<{ id: number; mark: RowMark; timer: number } | null>(null);
  /** Позиции условий с числами (метки отбора, §194) и выбранная; одна позиция — меток нет. */
  const [positions, setPositions] = useState<PositionCount[]>([]);
  /** Сколько пришло после прошлого захода (§200): больше нуля — метка «New since last visit», число не показывается. */
  const [recent, setRecent] = useState(0);
  /** Выбранный отбор: позиция и (или) «после прошлого захода». */
  const [filter, setFilter] = useState<ResultFilter>({});
  /** Полоса «Returned to search results» после «Return» (§199). */
  const [returned, setReturned] = useState(false);
  /** «Без ответа 3+ недели» и завершённые под «Моими откликами» (§196, §213, §217): свёрнуто, пока не раскрыли. */
  const [expanded, setExpanded] = useState(false);
  /** Набранный в поле поиска текст (§226); в {@code query} он попадает с задержкой. */
  const [typed, setTyped] = useState(query);
  /** Запрос, по которому показана страница: сменился — с первой страницы. */
  const shownQuery = useRef(query);
  /**
   * Текущие страница, размер и выбранная позиция — для отправки отметки по таймеру (аудит §197): функция таймера
   * создана при нажатии и видела бы значения того времени.
   */
  const view = useRef({ page: 1, size, filter });
  useEffect(() => {
    view.current = { page: data?.page ?? 1, size, filter };
  });

  async function show(page: number, rows: number, chosen: ResultFilter = filter) {
    setLoading(true);
    setMessage(null);
    try {
      let target = chosen;
      if (kind === 'results') {
        const labels = await resultFilters();
        setPositions(labels.positions);
        setRecent(labels.sinceLastVisit);
        const position = target.position !== undefined
          && labels.positions.some((item) => item.code === target.position) ? target.position : undefined;
        const sinceLastVisit = target.sinceLastVisit === true && labels.sinceLastVisit > 0 ? true : undefined;
        if (position !== target.position || sinceLastVisit !== target.sinceLastVisit) {
          target = { position, sinceLastVisit };
          setFilter(target);
        }
      }
      let next = await load(page, rows, target, query);
      if (next.items.length === 0 && next.total > 0 && page > 1) {
        next = await load(Math.ceil(next.total / rows), rows, target, query);
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

  /**
   * «Return» в строке «Не интересно»: вакансия уходит к прежнему статусу, страница перечитывается; внизу на
   * {@value UNDO_MS} мс «Returned to search results» (§199) — вакансия не пропадает молча.
   */
  async function giveBack(id: number) {
    try {
      await setStatus(id, 'RETURNED');
    } catch (failure) {
      setMessage(errorText(failure, t));
      return;
    }
    setReturned(true);
    window.setTimeout(() => setReturned(false), UNDO_MS);
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
    await show(view.current.page, view.current.size, view.current.filter);
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

  /**
   * Ожидающая отметка уходит сразу — по {@link flushMarks} (выход) и при уходе из списка в другой раздел; таймер
   * снимается, чтобы она не ушла второй раз.
   */
  useEffect(() => {
    async function flush() {
      const current = pending.current;
      if (current === null) {
        return;
      }
      window.clearTimeout(current.timer);
      pending.current = null;
      setWaiting(null);
      await setStatus(current.id, current.mark);
    }
    flushers.add(flush);
    return () => {
      flushers.delete(flush);
      void flush().catch(() => undefined);
    };
  }, []);

  /**
   * Метка позиции (§194) или «New since last visit» (§200): выбор — только эти вакансии с первой страницы; повторное
   * нажатие — снимает отбор; позиция и «после прошлого захода» сочетаются.
   */
  function choose(next: ResultFilter) {
    setFilter(next);
    show(1, size, next);
  }

  function resize(rows: number) {
    setSize(rows);
    storeSize(rows);
    show(1, rows);
  }

  /**
   * Смена языка перечитывает страницу: названия групп ISCO-08 в метках приходят на языке интерфейса (§204); новый
   * запрос поиска (§226) — с первой страницы.
   */
  useEffect(() => {
    const restart = shownQuery.current !== query;
    shownQuery.current = query;
    show(restart ? 1 : data?.page ?? 1, size);
  }, [reload, i18n.language, query]);

  /** Набранный текст уходит в поиск через {@value SEARCH_DELAY_MS} мс после последней буквы. */
  useEffect(() => {
    if (onQuery === undefined) {
      return;
    }
    const timer = window.setTimeout(() => onQuery(typed.trim()), SEARCH_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [typed]);

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
  const finished = kind === 'finished';
  /** Свёрнутая группа под «Моими откликами»: строка-заголовок с числом, раскрывается нажатием; пустая не показывается. */
  const collapsible = finished || kind === 'silent';
  const side = LAST_COLUMN[kind];
  if ((results && data.total === 0 && !hasConditions) || (collapsible && data.total === 0)) {
    return null;
  }
  const rowsShown = !collapsible || expanded;
  const collecting = results && data.total === 0 && poll && refreshes < REFRESH_TIMES;
  const hasCounts = positions.length > 1 && positions.some((item) => item.count > 0);
  return (
    <section className="list">
      {results ? data.total > 0 && (
        <div className="list-head">
          <h1>{data.total === 1 ? t('list.countOne') : t('list.count', { total: data.total })}</h1>
          {data.newCount > 0 && <span className="fresh">{t('list.newCount', { fresh: data.newCount })}</span>}
          <span className="muted">· {t('list.updatedHourly')}</span>
        </div>
      ) : collapsible ? (
        <button type="button" className="list-toggle" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
          <span aria-hidden="true">{expanded ? '▾' : '▸'}</span> {t(finished ? 'list.finished' : 'list.silent')}
          <span className="count-badge">{data.total}</span>
        </button>
      ) : title !== undefined && (
        <div className="list-head">
          <h1>{title}</h1>
          {data.total > 0 && (
            <span className="muted">· {headCount(kind, query, data.total, t)}</span>
          )}
        </div>
      )}
      {onQuery !== undefined && (
        <div className="list-search">
          <input type="text" value={typed} maxLength={SEARCH_MAX} placeholder={t('list.search')}
            aria-label={t('list.search')} onChange={(event) => setTyped(event.target.value)} />
          {typed !== '' && (
            <button type="button" className="row-mark" aria-label={t('list.searchClear')} title={t('list.searchClear')}
              onClick={() => setTyped('')}>
              ×
            </button>
          )}
        </div>
      )}
      {/* Метки — когда есть что отбирать: пока идёт первый сбор (все нули), строки нулей нет (§208). */}
      {results && (hasCounts || recent > 0 || filter.sinceLastVisit === true) && (
        <div className="position-filter" role="group" aria-label={t('list.byPosition')}>
          {(recent > 0 || filter.sinceLastVisit === true) && (
            <button type="button" aria-pressed={filter.sinceLastVisit === true}
              className={filter.sinceLastVisit === true ? 'chip current' : 'chip'} disabled={loading}
              onClick={() => choose({ ...filter, sinceLastVisit: filter.sinceLastVisit === true ? undefined : true })}>
              <span className="dot" aria-hidden="true" /> {t('list.sinceLastVisitFilter')}
            </button>
          )}
          {positions.length > 1 && hasCounts && positions.map((item) => (
            <button key={item.code} type="button" aria-pressed={filter.position === item.code}
              className={filter.position === item.code ? 'chip current' : 'chip'}
              disabled={loading || (item.count === 0 && filter.position !== item.code)}
              onClick={() => choose({ ...filter, position: filter.position === item.code ? undefined : item.code })}>
              {item.name} <span className="chip-count">{item.count}</span>
            </button>
          ))}
        </div>
      )}
      {data.total === 0 && kind !== 'results' && kind !== 'silent' && kind !== 'finished' && (
        <Empty title={query !== '' ? t('list.notFound', { query }) : t(EMPTY_TEXT[kind])} />
      )}
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
      {data.total > 0 && rowsShown && (
        <ul className="rows">
          {data.items.filter((item) => !dismissed.includes(item.id)).map((item) => {
            const fresh = results && item.timeline.status.code === 'NEW';
            const seen = (results && !fresh) || actions || finished;
            return (
              <li key={item.id} className="actionable">
                {results ? (
                  <a className={seen ? 'row seen' : 'row'} href={item.posting.url} target="_blank"
                    rel="noopener noreferrer" onClick={() => opened(item)}>
                    <RowContent item={item} fresh={fresh} side={side(item, i18n)} link
                      matches={positions.length > 1 ? item.posting.matches : []} />
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
      {returned && waiting === null && (
        <div className="undo-bar" role="status"><span>{t('list.returned')}</span></div>
      )}
      {waiting !== null && (
        <div className="undo-bar" role="status">
          <span>{t(MARK_MOVED[waiting])}</span>
          <button type="button" className="link" onClick={undo}>{t('list.undo')}</button>
        </div>
      )}
      {data.total > 0 && rowsShown && (
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
 * Число рядом с заголовком раздела (§227): идёт поиск — «N found»; в «Моих откликах» — «N active» (действующие:
 * «Interview» и «Applied» моложе 3 недель по открытым вакансиям — закрытые и долго молчащие ниже, в своих группах);
 * иначе — «N vacancies».
 */
function headCount(kind: ListKind, query: string, total: number, t: I18n['t']): string {
  if (query !== '') {
    return t('list.foundCount', { total });
  }
  if (kind === 'responses') {
    return t('list.active', { total });
  }
  return total === 1 ? t('list.countOne') : t('list.count', { total });
}

/**
 * Содержимое строки (§171): позиция с пометками, «работодатель · страна», справа — дата или статус. {@code link} —
 * строка ведёт в публикацию (§193): у позиции «↗». {@code matches} — к каким позициям условий подошла (§194; при
 * нескольких позициях в условиях) — после «работодатель · страна», плашками того же вида, что метки над списком, без
 * числа (решение владельца 2026-10-26).
 */
function RowContent({ item, fresh, side, link = false, matches = [] }: { item: VacancyItem; fresh: boolean;
  side: ReactNode; link?: boolean; matches?: string[] }) {
  const i18n = useI18n();
  const { t } = i18n;
  const meta = rowMetaText(item.parties.employer, item.work, i18n);
  return (
    <>
      <span className="row-main">
        <span className="row-title">
          {fresh && <span className="pill pill-fresh">{t('list.new')}</span>}
          <span className="position">{item.posting.position}</span>
          {link && <span className="row-link" title={t('vacancy.openPosting')} aria-hidden="true">↗</span>}
          {item.work.format === 'OFFICE' && <span className="pill pill-muted">{t('format.office')}</span>}
          {item.work.format === 'REMOTE' && <span className="pill pill-muted">{t('format.remote')}</span>}
          {item.timeline.status.closed && <span className="pill pill-muted">{t('vacancy.closed')}</span>}
        </span>
        <span className="row-meta">{meta}</span>
        {/* Роли — своей строкой под «работодатель · страна»: не смешиваются с ней (решение владельца, аудит §212). */}
        {matches.length > 0 && (
          <span className="row-roles">
            {matches.map((name) => <span key={name} className="row-match">{name}</span>)}
          </span>
        )}
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
