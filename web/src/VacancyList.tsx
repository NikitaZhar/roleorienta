import { useEffect, useState } from 'react';
import { searchCondition, type VacancyItem, type VacancyPage } from './api';
import { countriesText, openedText } from './format';

interface Props {
  title: string;
  load: (cursor: string | null) => Promise<VacancyPage>;
  /** Список отмеченных «не подходит»: пустой объясняется иначе, условия не спрашиваются. */
  marked: boolean;
  /** Список открыт сразу после новых условий: пустой перечитывается, пока не придёт первая порция. */
  poll: boolean;
  onOpen: (id: number) => void;
}

/** Пустой накопленный список перечитывается: первая порция новой версии условий приходит в течение ~30 с (§120). */
const REFRESH_MS = 5000;
const REFRESH_TIMES = 24;

/**
 * Список вакансий страницами по курсору («Load more»): накопленный список (открытые позже сверху, дата открытия —
 * §127) или отмеченные «не подходит». Пустой накопленный список объясняется: что искать не задано — задать выше; задано — подходящих вакансий
 * пока нет (проходы выдачи раз в час). Сразу после новых условий ({@code poll}) пустой список перечитывается каждые
 * 5 с в течение 2 минут — первая порция выдаётся в течение ~30 с (§120); следующий запрос — только после ответа на
 * предыдущий (аудит §121). При обычном открытии раздела опроса нет. Точная причина
 * пустого списка (обход стран не завершён и т. п.) — шаг 1.11б.
 */
export function VacancyList({ title, load, marked, poll, onOpen }: Props) {
  const [items, setItems] = useState<VacancyItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [hasConditions, setHasConditions] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshes, setRefreshes] = useState(0);

  async function more(from: string | null) {
    setLoading(true);
    try {
      const next = await load(from);
      setItems((current) => (from === null ? next.items : appendNew(current, next.items)));
      setCursor(next.nextCursor);
      if (from === null && next.items.length === 0 && !marked) {
        setHasConditions((await searchCondition()) !== null);
      }
      setLoaded(true);
    } catch (failure) {
      setMessage((failure as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    more(null);
  }, []);

  useEffect(() => {
    if (!poll || !loaded || loading || !hasConditions || items.length > 0 || refreshes >= REFRESH_TIMES) {
      return;
    }
    const timer = setTimeout(() => {
      setRefreshes((count) => count + 1);
      more(null);
    }, REFRESH_MS);
    return () => clearTimeout(timer);
  }, [poll, loaded, loading, hasConditions, items, refreshes]);

  if (message !== null) {
    return <p className="error">{message}</p>;
  }
  if (!loaded) {
    return <p>Loading…</p>;
  }
  return (
    <section>
      <h2>{title}</h2>
      {items.length === 0 && marked && <p>No vacancies marked as not suitable.</p>}
      {items.length === 0 && !marked && !hasConditions && <p>Choose a country and a vacancy above and press Search.</p>}
      {items.length === 0 && !marked && hasConditions && (
        <p>{poll && refreshes < REFRESH_TIMES ? 'Searching…'
          : 'No matching vacancies yet. New vacancies are added to the list every hour.'}</p>
      )}
      <ul className="vacancies">
        {items.map((item) => (
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
      {cursor !== null && (
        <button type="button" disabled={loading} onClick={() => more(cursor)}>Load more</button>
      )}
    </section>
  );
}

/**
 * Следующая страница без уже показанных вакансий: порядок — по дате открытия, а она может появиться у вакансии между
 * загрузками страниц, и вакансия придёт снова (аудит §133).
 */
function appendNew(current: VacancyItem[], next: VacancyItem[]): VacancyItem[] {
  const shown = new Set(current.map((item) => item.id));
  return [...current, ...next.filter((item) => !shown.has(item.id))];
}
