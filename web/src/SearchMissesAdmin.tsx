import { useEffect, useState } from 'react';
import { forgetSearchMiss, type SearchMiss, searchMisses } from './api';

/**
 * Что искали и не нашли (§205), в нынешнем виде страницы /admin: запросы подсказки позиции без результата — чаще
 * первыми. «Add position» открывает форму новой позиции словаря ниже с этим названием (§206; с группой ISCO-08);
 * «Done» убирает запрос из списка (повторится — появится снова). Число запросов — у вкладки Positions.
 */
export function SearchMissesAdmin({ onAdd, onCount }: {
  onAdd: (query: string) => void;
  onCount: (count: number) => void;
}) {
  const [items, setItems] = useState<SearchMiss[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function load() {
    setMessage(null);
    try {
      const loaded = await searchMisses();
      setItems(loaded);
      onCount(loaded.length);
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function forget(query: string) {
    setMessage(null);
    try {
      await forgetSearchMiss(query);
      const left = (items ?? []).filter((item) => item.query !== query);
      setItems(left);
      onCount(left.length);
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  return (
    <section>
      <h2>Searched, not found</h2>
      <p className="muted">Position searches that found nothing. If a position is needed, press Add position; when
        it is added or not needed, press Done.</p>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {items !== null && (items.length === 0 ? <p className="muted">Nothing yet.</p> : (
        <div className="scroll">
          <table className="companies">
            <thead>
              <tr><th>Search</th><th>Times</th><th>Last</th><th><span className="sr-only">Actions</span></th></tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.query}>
                  <td>{item.query}</td>
                  <td>{item.misses}</td>
                  <td className="nowrap">{new Date(item.lastAt).toLocaleDateString('en-GB')}</td>
                  <td className="row-actions nowrap">
                    <button type="button" onClick={() => onAdd(item.query)}>Add position</button>
                    <button type="button" onClick={() => forget(item.query)}>Done</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
      <button type="button" onClick={load}>Refresh</button>
    </section>
  );
}
