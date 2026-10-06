import { type FormEvent, useEffect, useState } from 'react';
import {
  ApiError, type CatalogItem, countries, positions, saveSearchCondition, searchCondition, type WorkFormat,
} from './api';

const FORMATS: { value: WorkFormat | ''; label: string }[] = [
  { value: '', label: 'Any' },
  { value: 'OFFICE', label: 'Office' },
  { value: 'HYBRID', label: 'Hybrid' },
  { value: 'REMOTE', label: 'Remote' },
];

/**
 * Условия поиска (бизнес-описание §7.2): страны, позиция словаря, формат, лимит порции.
 * Версия условий — ETag; сохранение передаёт её в If-Match. Смена стран, позиции или формата
 * создаёт новую версию (прежний накопленный список не используется), смена лимита — нет.
 */
export function ConditionsPage() {
  const [catalog, setCatalog] = useState<CatalogItem[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [position, setPosition] = useState<CatalogItem | null>(null);
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<CatalogItem[]>([]);
  const [format, setFormat] = useState<WorkFormat | ''>('');
  const [limit, setLimit] = useState('');
  const [etag, setEtag] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  async function load() {
    const [all, current] = await Promise.all([countries(), searchCondition()]);
    setCatalog(all);
    setEtag(current?.etag ?? null);
    if (current !== null) {
      const condition = current.data;
      setSelected(condition.countries);
      setFormat(condition.format ?? '');
      setLimit(String(condition.portionLimit));
      const matches = await positions(condition.position);
      setPosition(matches.find((item) => item.code === condition.position)
        ?? { code: condition.position, name: condition.position });
    }
    setLoaded(true);
  }

  useEffect(() => {
    load().catch((failure: Error) => setMessage(failure.message));
  }, []);

  useEffect(() => {
    if (query.trim().length < 2) {
      setFound([]);
      return;
    }
    let current = true;
    const timer = setTimeout(() => {
      positions(query.trim()).then((items) => {
        if (current) {
          setFound(items);
        }
      }, (failure: Error) => setMessage(failure.message));
    }, 300);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [query]);

  function toggle(code: string) {
    setSelected((current) => current.includes(code)
      ? current.filter((item) => item !== code) : [...current, code]);
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (position === null) {
      setMessage('Choose a position');
      return;
    }
    try {
      const saved = await saveSearchCondition({
        countries: selected,
        position: position.code,
        format: format === '' ? null : format,
        portionLimit: limit.trim() === '' ? null : Number(limit),
      }, etag);
      setEtag(saved.etag);
      setLimit(String(saved.data.portionLimit));
      setMessage('Saved');
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 412) {
        setMessage('Conditions were changed in another window; reloaded, check and save again');
        await load().catch((reload: Error) => setMessage(reload.message));
        return;
      }
      setMessage(failure instanceof ApiError && failure.fields.length > 0
        ? failure.fields.map((field) => `${field.pointer.substring(1)}: ${field.detail}`).join('; ')
        : (failure as Error).message);
    }
  }

  if (!loaded) {
    return message === null ? <p>Loading…</p> : <p className="error">{message}</p>;
  }
  return (
    <form className="narrow-form" onSubmit={save}>
      <h2>Search conditions</h2>
      <fieldset>
        <legend>Countries</legend>
        {catalog.map((country) => (
          <label key={country.code} className="inline">
            <input type="checkbox" checked={selected.includes(country.code)}
              onChange={() => toggle(country.code)} />
            {country.name}
          </label>
        ))}
      </fieldset>
      <label>
        Position {position !== null && <strong>— {position.name}</strong>}
        <input type="search" placeholder="Type at least 2 letters" value={query}
          onChange={(event) => setQuery(event.target.value)} />
      </label>
      {found.length > 0 && (
        <ul className="choices">
          {found.map((item) => (
            <li key={item.code}>
              <button type="button" onClick={() => {
                setPosition(item);
                setQuery('');
              }}>{item.name}</button>
            </li>
          ))}
        </ul>
      )}
      <label>
        Work format
        <select value={format} onChange={(event) => setFormat(event.target.value as WorkFormat | '')}>
          {FORMATS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
      </label>
      <label>
        Vacancies per portion (1–100; empty — default)
        <input type="number" min={1} max={100} value={limit} onChange={(event) => setLimit(event.target.value)} />
      </label>
      {message !== null && <p className={message === 'Saved' ? 'ok' : 'error'} role="status">{message}</p>}
      <div className="actions">
        <button type="submit">Save</button>
      </div>
    </form>
  );
}
