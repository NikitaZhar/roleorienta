import { useEffect, useState } from 'react';
import {
  type ChosenWithoutVacancies, chosenWithoutVacancies, forgetSearchMiss, type SearchMiss, searchMisses,
} from './api';

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

/**
 * Выбрано в условиях — вакансий нет (§214; аудит логики §212, п. 8): позиция или группа, выбранная в действующих
 * условиях, по которой нет ни одной открытой вакансии. Пользователь выбрал, но ничего не получает — администратор
 * решает: поправить названия позиции в словаре или подождать (группе без кодов поможет распознавание по названию).
 */
export function ChosenWithoutVacanciesAdmin() {
  const [items, setItems] = useState<ChosenWithoutVacancies[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    chosenWithoutVacancies().then(setItems, (failure: Error) => setMessage(failure.message));
  }, []);

  return (
    <section>
      <h2>Chosen, no vacancies</h2>
      <p className="muted">Positions and groups that users chose in their search, with no open vacancy at all.</p>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {items !== null && (items.length === 0 ? <p className="muted">None: every choice has vacancies.</p> : (
        <div className="scroll">
          <table className="companies">
            <thead><tr><th>Position or group</th><th>Code</th><th>Chosen by</th></tr></thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.code}>
                  <td>{item.name}</td>
                  <td className="muted nowrap">{item.code}</td>
                  <td>{item.conditions === 1 ? '1 search' : item.conditions + ' searches'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  );
}
