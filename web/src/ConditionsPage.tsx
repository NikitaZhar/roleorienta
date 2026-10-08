import { type FormEvent, useEffect, useState } from 'react';
import {
  ApiError, type CatalogItem, countries, positions, saveSearchCondition, searchCondition, type WorkFormat,
} from './api';

interface Props {
  onSaved: () => void;
}

/**
 * Страны, формат и лимит порции текущих условий: экран показывает одну страну, формат и лимит не показывает. Пока
 * выбрана первая из сохранённых стран, сохраняются все прежние страны — условия с несколькими странами (экран §63) не
 * теряют стран и не получают новую версию без изменения (аудит §78).
 */
interface Kept {
  countries: string[];
  format: WorkFormat | null;
  portionLimit: number | null;
}

/**
 * Что искать: страна и позиция словаря (бизнес-описание §7.2; формат работы и лимит порции на этом этапе не
 * показываются — сохраняются прежними, у новых условий — значения сервера). Версия условий — ETag; сохранение
 * передаёт её в If-Match. Смена страны или позиции создаёт новую версию условий — накопленный список начинается
 * заново.
 */
export function ConditionsPage({ onSaved }: Props) {
  const [catalog, setCatalog] = useState<CatalogItem[]>([]);
  const [country, setCountry] = useState('');
  const [position, setPosition] = useState<CatalogItem | null>(null);
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<CatalogItem[]>([]);
  const [kept, setKept] = useState<Kept>({ countries: [], format: null, portionLimit: null });
  const [etag, setEtag] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  async function load() {
    const [all, current] = await Promise.all([countries(), searchCondition()]);
    setCatalog(all);
    setEtag(current?.etag ?? null);
    if (current === null) {
      setCountry(all.length > 0 ? all[0].code : '');
    } else {
      const condition = current.data;
      setCountry(condition.countries[0] ?? '');
      setKept({ countries: condition.countries, format: condition.format, portionLimit: condition.portionLimit });
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
      }, (failure: Error) => {
        if (current) {
          setMessage(failure.message);
        }
      });
    }, 300);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [query]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (country === '' || position === null) {
      setMessage('Choose a country and a position');
      return;
    }
    try {
      const saved = await saveSearchCondition({
        countries: country === kept.countries[0] ? kept.countries : [country],
        position: position.code,
        format: kept.format,
        portionLimit: kept.portionLimit,
      }, etag);
      setEtag(saved.etag);
      setKept({ countries: saved.data.countries, format: saved.data.format, portionLimit: saved.data.portionLimit });
      setMessage(null);
      onSaved();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 412) {
        setMessage('Search was changed in another window; reloaded, check and search again');
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
      <label>
        Country
        <select value={country} onChange={(event) => setCountry(event.target.value)}>
          {catalog.map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}
        </select>
      </label>
      <label>
        Vacancy to look for {position !== null && <strong>— {position.name}</strong>}
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
      {message !== null && <p className="error" role="status">{message}</p>}
      <div className="actions">
        <button type="submit">Search</button>
      </div>
    </form>
  );
}
