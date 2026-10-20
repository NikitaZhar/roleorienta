import { useEffect, useState } from 'react';
import { searchCondition, type VacancyPage } from './api';
import { openedText, rowCountryText } from './format';
import { PageSize, Pager } from './Pager';

interface Props {
  /** Заголовок раздела; нет — без заголовка (результаты под условиями поиска). */
  title?: string;
  load: (page: number, size: number) => Promise<VacancyPage>;
  /** Список отмеченных «Не интересно»: пустой объясняется иначе, условия не спрашиваются, число не показывается. */
  marked: boolean;
  /** Список открыт сразу после новых условий: пустой перечитывается, пока не придёт первая порция. */
  poll: boolean;
  /** Счётчик перечитываний: изменился — перечитать текущую страницу (отметка поставлена или снята). */
  reload: number;
  onOpen: (id: number) => void;
}

/** Пустой накопленный список перечитывается: первая порция новой версии условий приходит в течение ~30 с (§120). */
const REFRESH_MS = 5000;
const REFRESH_TIMES = 24;

/** Размеры страницы на выбор (решение владельца 2026-10-20, §147) и размер по умолчанию. */
const PAGE_SIZES = [20, 50, 100];
const DEFAULT_SIZE = 20;
/** Ключ выбранного размера в хранилище браузера. */
const SIZE_KEY = 'roleorienta.pageSize';

/**
 * Список вакансий страницами по номеру (§151) строками-колонками «Company · Position · Country · Opened» (агентство —
 * только в сведениях; на телефоне строка — плитка в две строчки, §147, §152): накопленный список (открытые позже сверху, дата открытия — §127; над
 * списком — «Found N») или отмеченные «Не интересно». Внизу — номера страниц и выбор размера страницы; выбор
 * запоминается в браузере ({@code localStorage}: хранилище браузера, переживает перезагрузку; недоступно —
 * размер по умолчанию). Пустой накопленный список объясняется: что искать не задано — задать выше; задано —
 * подходящих вакансий пока нет. Сразу после новых условий ({@code poll}) пустая первая страница перечитывается каждые
 * 5 с в течение 2 минут — первая порция выдаётся в течение ~30 с (§120); следующий запрос — только после ответа на
 * предыдущий (аудит §121). Перечитывание ({@code reload}) показывает ту же страницу; если она опустела (снята
 * последняя отметка на последней странице) — последнюю непустую. Ошибка загрузки — сообщение с «Retry»; размер
 * страницы во время загрузки не меняется (аудит §154).
 */
export function VacancyList({ title, load, marked, poll, reload, onOpen }: Props) {
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
      if (next.total === 0 && !marked) {
        setHasConditions((await searchCondition()) !== null);
      }
    } catch (failure) {
      setMessage((failure as Error).message);
    } finally {
      setLoading(false);
    }
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
        {message} <button type="button" onClick={() => show(data?.page ?? 1, size)}>Retry</button>
      </p>
    );
  }
  if (data === null) {
    return <p>Loading…</p>;
  }
  return (
    <section>
      {title !== undefined && <h2>{title}</h2>}
      {!marked && data.total > 0 && <p className="count">Found {data.total}</p>}
      {data.total === 0 && marked && <Empty text="Vacancies you mark as not interested will appear here" />}
      {data.total === 0 && !marked && !hasConditions && <Empty text="No results yet" />}
      {data.total === 0 && !marked && hasConditions && (
        <Empty text={poll && refreshes < REFRESH_TIMES ? 'Searching…'
          : 'No matching vacancies yet. New vacancies are added to the list every hour.'} />
      )}
      {data.total > 0 && (
        <div className="rows">
          <div className="row head" aria-hidden="true">
            <span>Company</span><span>Position</span><span>Country</span><span>Opened</span>
          </div>
          <ul>
            {data.items.map((item) => (
              <li key={item.id}>
                <button type="button" className="row" onClick={() => onOpen(item.id)}>
                  <span className="company">{item.parties.employer ?? 'Employer not specified'}</span>
                  <span className="position">{item.position}</span>
                  <span className="country">{rowCountryText(item.work)}</span>
                  <span className="opened">{openedText(item.opened)}</span>
                </button>
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

/** Сохранённый размер страницы; нет, не из списка или хранилище недоступно — по умолчанию. */
function storedSize(): number {
  try {
    const stored = Number(localStorage.getItem(SIZE_KEY));
    return PAGE_SIZES.includes(stored) ? stored : DEFAULT_SIZE;
  } catch {
    return DEFAULT_SIZE;
  }
}

function storeSize(size: number) {
  try {
    localStorage.setItem(SIZE_KEY, String(size));
  } catch {
    // Хранилище недоступно (приватный режим): выбор действует до перезагрузки.
  }
}
