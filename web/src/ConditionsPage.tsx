import { type FormEvent, useEffect, useState } from 'react';
import {
  ApiError, type CatalogItem, countries, positions, saveSearchCondition, searchCondition, type WorkFormat,
} from './api';

interface Props {
  onSaved: () => void;
}

const FORMATS: { value: WorkFormat | ''; label: string }[] = [
  { value: '', label: 'Any' },
  { value: 'OFFICE', label: 'Office' },
  { value: 'REMOTE', label: 'Remote' },
];

/** Позиций в условиях не больше (§126; как в API). */
const MAX_POSITIONS = 10;

/**
 * Условия поиска (бизнес-описание §7.2): страны, позиции словаря (одна или несколько — вакансия подходит к любой из
 * них; выбранные — метками с «×», §126), формат работы (Any — все форматы; гибрида нет —
 * гибридные вакансии выдаются при любом формате, §125). Нет условий —
 * отмечена первая страна из поддерживаемых. Версия условий — ETag; сохранение передаёт её в If-Match. Смена стран,
 * набора позиций или формата создаёт новую версию условий: список начинается заново и сразу получает все подходящие вакансии.
 * Лимита порции на экране нет (решение владельца 2026-10-15, §120): API его принимает, выдача его не применяет.
 */
export function ConditionsPage({ onSaved }: Props) {
  const [catalog, setCatalog] = useState<CatalogItem[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [chosen, setChosen] = useState<CatalogItem[]>([]);
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<CatalogItem[]>([]);
  const [format, setFormat] = useState<WorkFormat | ''>('');
  const [etag, setEtag] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  async function load() {
    const [all, current] = await Promise.all([countries(), searchCondition()]);
    setCatalog(all);
    setEtag(current?.etag ?? null);
    if (current === null) {
      setSelected(all.length > 0 ? [all[0].code] : []);
    } else {
      const condition = current.data;
      setSelected(condition.countries);
      setFormat(condition.format ?? '');
      setChosen(await Promise.all(condition.positions.map(async (code) => {
        const matches = await positions(code);
        return matches.find((item) => item.code === code) ?? { code, name: code };
      })));
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

  function toggle(code: string) {
    setSelected((current) => current.includes(code)
      ? current.filter((item) => item !== code) : [...current, code]);
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selected.length === 0 || chosen.length === 0) {
      setMessage('Choose at least one country and a vacancy');
      return;
    }
    try {
      const saved = await saveSearchCondition({
        countries: selected,
        positions: chosen.map((item) => item.code),
        format: format === '' ? null : format,
        portionLimit: null,
      }, etag);
      setEtag(saved.etag);
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
        Vacancies to look for
        <input type="search" placeholder="Type at least 2 letters" value={query}
          disabled={chosen.length >= MAX_POSITIONS} onChange={(event) => setQuery(event.target.value)} />
      </label>
      {chosen.length > 0 && (
        <ul className="chips">
          {chosen.map((item) => (
            <li key={item.code}>
              {item.name}
              <button type="button" aria-label={'Remove ' + item.name}
                onClick={() => setChosen((current) => current.filter((other) => other.code !== item.code))}>×</button>
            </li>
          ))}
        </ul>
      )}
      {found.length > 0 && (
        <ul className="choices">
          {found.filter((item) => !chosen.some((other) => other.code === item.code)).map((item) => (
            <li key={item.code}>
              <button type="button" onClick={() => {
                setChosen((current) => current.length >= MAX_POSITIONS ? current : [...current, item]);
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
      {message !== null && <p className="error" role="status">{message}</p>}
      <div className="actions">
        <button type="submit">Search</button>
      </div>
    </form>
  );
}
