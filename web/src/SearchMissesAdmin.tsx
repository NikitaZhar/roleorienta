import { useEffect, useState } from 'react';
import { forgetSearchMiss, type SearchMiss, searchMisses } from './api';

/**
 * Что искали и не нашли (§205), в нынешнем виде страницы /admin: запросы подсказки позиции без результата — чаще
 * первыми. Нужна точная позиция — администратор добавляет её в «Position dictionary» ниже (с группой ISCO-08); затем
 * «Done» убирает запрос из списка (повторится — появится снова).
 */
export function SearchMissesAdmin() {
  const [items, setItems] = useState<SearchMiss[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function load() {
    setMessage(null);
    try {
      setItems(await searchMisses());
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
      setItems((current) => current?.filter((item) => item.query !== query) ?? null);
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  return (
    <section>
      <h2>Searched, not found</h2>
      <p className="muted">Position searches that found nothing. If a position is needed, add it to the dictionary
        below, then press Done.</p>
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
