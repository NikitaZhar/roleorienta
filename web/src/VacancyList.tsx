import { useEffect, useState } from 'react';
import { searchCondition, type VacancyItem, type VacancyPage } from './api';
import { countriesText, dateText, formatText } from './format';

interface Props {
  title: string;
  load: (cursor: string | null) => Promise<VacancyPage>;
  emptyMarked: boolean;
  onOpen: (id: number) => void;
  onConditions: () => void;
}

/**
 * Список вакансий страницами по курсору («Load more»): накопленный список или отмеченные «не подходит».
 * Пустой накопленный список объясняется: нет условий — задать их; условия есть — подходящих вакансий пока
 * нет (проходы выдачи раз в час). Точная причина (обход стран не завершён и т. п.) — подэтап 1.10.
 */
export function VacancyList({ title, load, emptyMarked, onOpen, onConditions }: Props) {
  const [items, setItems] = useState<VacancyItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [hasConditions, setHasConditions] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function more(from: string | null) {
    setLoading(true);
    try {
      const next = await load(from);
      setItems((current) => (from === null ? next.items : [...current, ...next.items]));
      setCursor(next.nextCursor);
      if (from === null && next.items.length === 0 && !emptyMarked) {
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
  }, [load]);

  if (message !== null) {
    return <p className="error">{message}</p>;
  }
  if (!loaded) {
    return <p>Loading…</p>;
  }
  return (
    <section>
      <h2>{title}</h2>
      {items.length === 0 && emptyMarked && <p>No vacancies are marked as not suitable.</p>}
      {items.length === 0 && !emptyMarked && !hasConditions && (
        <p>
          Search conditions are not set yet.{' '}
          <button type="button" onClick={onConditions}>Set conditions</button>
        </p>
      )}
      {items.length === 0 && !emptyMarked && hasConditions && (
        <p>No matching vacancies yet. New vacancies are added to the list every hour.</p>
      )}
      <ul className="vacancies">
        {items.map((item) => (
          <li key={item.id}>
            <button type="button" className="vacancy" onClick={() => onOpen(item.id)}>
              <strong>{item.position}</strong>
              <span>{item.parties.employer ?? 'Employer not specified'}
                {item.parties.agency !== null && ` · via ${item.parties.agency}`}</span>
              <span className="muted">{countriesText(item.work)} · {formatText(item.work)} · {dateText(item.listedAt)}</span>
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
