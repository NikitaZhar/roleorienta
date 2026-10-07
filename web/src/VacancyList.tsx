import { useEffect, useState } from 'react';
import { searchCondition, type VacancyItem, vacancies } from './api';
import { countriesText, dateText, formatText } from './format';

interface Props {
  onOpen: (id: number) => void;
}

/**
 * Накопленный список страницами по курсору («Load more»), новые сверху. Пустой список объясняется: что искать
 * не задано — задать выше; задано — подходящих вакансий пока нет (проходы выдачи раз в час). Точная причина
 * (обход стран не завершён и т. п.) — подэтап 1.10.
 */
export function VacancyList({ onOpen }: Props) {
  const [items, setItems] = useState<VacancyItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [hasConditions, setHasConditions] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function more(from: string | null) {
    setLoading(true);
    try {
      const next = await vacancies(from);
      setItems((current) => (from === null ? next.items : [...current, ...next.items]));
      setCursor(next.nextCursor);
      if (from === null && next.items.length === 0) {
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

  if (message !== null) {
    return <p className="error">{message}</p>;
  }
  if (!loaded) {
    return <p>Loading…</p>;
  }
  return (
    <section>
      <h2>Vacancies</h2>
      {items.length === 0 && !hasConditions && <p>Choose a country and a vacancy above and press Search.</p>}
      {items.length === 0 && hasConditions && (
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
