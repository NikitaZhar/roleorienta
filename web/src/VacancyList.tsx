import { useEffect, useState } from 'react';
import { searchCondition, type VacancyPage } from './api';
import { countriesText, openedText } from './format';
import { PageSize, Pager } from './Pager';

interface Props {
  title: string;
  load: (page: number, size: number) => Promise<VacancyPage>;
  /** Список отмеченных «Не интересно»: пустой объясняется иначе, условия не спрашиваются, число не показывается. */
  marked: boolean;
  /** Список открыт сразу после новых условий: пустой перечитывается, пока не придёт первая порция. */
  poll: boolean;
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
 * Список вакансий страницами по номеру (§151): накопленный список (открытые позже сверху, дата открытия — §127; над
 * списком — «Found N») или отмеченные «Не интересно». Внизу — номера страниц и выбор размера страницы; выбор
 * запоминается в браузере ({@code localStorage}: хранилище браузера, переживает перезагрузку; недоступно —
 * размер по умолчанию). Пустой накопленный список объясняется: что искать не задано — задать выше; задано —
 * подходящих вакансий пока нет. Сразу после новых условий ({@code poll}) пустая первая страница перечитывается каждые
 * 5 с в течение 2 минут — первая порция выдаётся в течение ~30 с (§120); следующий запрос — только после ответа на
 * предыдущий (аудит §121).
 */
export function VacancyList({ title, load, marked, poll, onOpen }: Props) {
  const [data, setData] = useState<VacancyPage | null>(null);
  const [size, setSize] = useState(storedSize);
  const [hasConditions, setHasConditions] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshes, setRefreshes] = useState(0);

  async function show(page: number, rows: number) {
    setLoading(true);
    try {
      const next = await load(page, rows);
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
    show(1, size);
  }, []);

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
    return <p className="error">{message}</p>;
  }
  if (data === null) {
    return <p>Loading…</p>;
  }
  return (
    <section>
      <h2>{title}</h2>
      {!marked && data.total > 0 && <p className="count">Found {data.total}</p>}
      {data.total === 0 && marked && <p>Vacancies you mark as not interested will appear here.</p>}
      {data.total === 0 && !marked && !hasConditions && <p>Choose a country and a vacancy above and press Search.</p>}
      {data.total === 0 && !marked && hasConditions && (
        <p>{poll && refreshes < REFRESH_TIMES ? 'Searching…'
          : 'No matching vacancies yet. New vacancies are added to the list every hour.'}</p>
      )}
      <ul className="vacancies">
        {data.items.map((item) => (
          <li key={item.id}>
            <button type="button" className="vacancy" onClick={() => onOpen(item.id)}>
              <strong>{item.position}</strong>
              <span>{item.parties.employer ?? 'Employer not specified'}
                {item.parties.agency !== null && ` · via ${item.parties.agency}`}</span>
              <span className="muted">{countriesText(item.work)} · {openedText(item.opened)}</span>
            </button>
          </li>
        ))}
      </ul>
      {data.total > 0 && (
        <div className="list-footer">
          <Pager page={data.page} pages={Math.ceil(data.total / data.size)} busy={loading}
            onPage={(page) => show(page, size)} />
          <PageSize size={size} sizes={PAGE_SIZES} onSize={resize} />
        </div>
      )}
    </section>
  );
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
