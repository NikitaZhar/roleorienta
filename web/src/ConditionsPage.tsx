import { type FormEvent, useEffect, useState } from 'react';
import {
  ApiError, type CatalogItem, countries, positions, saveSearchCondition, searchCondition, type WorkFormat,
} from './api';
import { PositionSuggestions } from './PositionSuggestions';

interface Props {
  /** Условия сохранены; {@code changed} — создана новая версия условий (ETag сменился). */
  onSaved: (changed: boolean) => void;
}

const FORMATS: { value: WorkFormat | ''; label: string }[] = [
  { value: '', label: 'Any' },
  { value: 'OFFICE', label: 'Office' },
  { value: 'REMOTE', label: 'Remote' },
];

/** Позиций в условиях не больше (§126; как в API). */
const MAX_POSITIONS = 10;

/**
 * Условия поиска (бизнес-описание §3, §7.2): страны, позиции словаря (одна или несколько — вакансия подходит к любой из
 * них; выбранные — метками с «×», §126), формат работы (Any — все форматы; гибрида нет —
 * гибридные вакансии выдаются при любом формате, §125). Экран — по согласованному макету (§147, §152): условия
 * заданы — свёрнуты в строку меток с «Edit»; «Edit» раскрывает форму с «Cancel»; после «Search» форма снова
 * свёрнута. Нет условий — «No search criteria yet», форма раскрыта, ни одна страна не отмечена (решение владельца
 * 2026-10-20). Условия сменились в другом окне ({@code 412}) — форма остаётся раскрытой с перечитанными условиями и
 * сообщением, список под ней начинается заново по новым условиям (аудит §154). Новая версия условий — когда выбор
 * (страны, позиции, формат) отличается от сохранённого: так же решает сервер (ETag меняется и от лимита порции, которого
 * на экране нет). Версия условий — ETag; сохранение передаёт её в If-Match. Смена стран, набора позиций или формата
 * создаёт новую версию условий: список начинается заново и сразу получает все подходящие вакансии.
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
  const [editing, setEditing] = useState(false);
  /** Выбор сохранённых условий; {@code null} — условий нет. Сравнение с ним показывает, создана ли новая версия. */
  const [savedSelection, setSavedSelection] = useState<string | null>(null);

  async function load() {
    const [all, current] = await Promise.all([countries(), searchCondition()]);
    setCatalog(all);
    setEtag(current?.etag ?? null);
    setQuery('');
    if (current === null) {
      setSelected([]);
      setChosen([]);
      setFormat('');
      setSavedSelection(null);
      setEditing(true);
    } else {
      const condition = current.data;
      setSavedSelection(selectionKey(condition.countries, condition.positions, condition.format ?? ''));
      setSelected(condition.countries);
      setFormat(condition.format ?? '');
      setChosen(await Promise.all(condition.positions.map(async (code) => {
        const matches = await positions(code);
        // Нет среди предлагаемых — позиция выключена администратором (§160): остаётся в условиях с пометкой.
        return matches.find((item) => item.code === code) ?? { code, name: code + ' (retired)' };
      })));
      setEditing(false);
    }
    setLoaded(true);
  }

  function cancel() {
    setMessage(null);
    load().catch((failure: Error) => setMessage(failure.message));
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
      const submitted = selectionKey(selected, chosen.map((item) => item.code), format);
      const changed = submitted !== savedSelection;
      setSavedSelection(submitted);
      setEtag(saved.etag);
      setMessage(null);
      setEditing(false);
      onSaved(changed);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 412) {
        setMessage('Search was changed in another window; reloaded, check and search again');
        await load().catch((reload: Error) => setMessage(reload.message));
        setEditing(true);
        onSaved(true);
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
  if (!editing) {
    return (
      <div className="filters-summary">
        <dl className="criteria" aria-label="Search criteria">
          <div>
            <dt>{selected.length > 1 ? 'Countries' : 'Country'}</dt>
            <dd><ul className="chips">
              {selected.map((code) => <li key={code}>{catalog.find((item) => item.code === code)?.name ?? code}</li>)}
            </ul></dd>
          </div>
          <div>
            <dt>{chosen.length > 1 ? 'Positions' : 'Position'}</dt>
            <dd><ul className="chips">{chosen.map((item) => <li key={item.code}>{item.name}</li>)}</ul></dd>
          </div>
          <div>
            <dt>Format</dt>
            <dd><ul className="chips"><li>{FORMATS.find((item) => item.value === format)?.label}</li></ul></dd>
          </div>
        </dl>
        <button type="button" onClick={() => setEditing(true)}>Edit</button>
      </div>
    );
  }
  return (
    <form className="filters" onSubmit={save}>
      {etag === null && <p className="lead">No search criteria yet. Choose a country and a vacancy.</p>}
      <div className="filters-row">
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
        <div className="positions">
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
          <PositionSuggestions />
        </div>
        <label>
          Work format
          <select value={format} onChange={(event) => setFormat(event.target.value as WorkFormat | '')}>
            {FORMATS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
      </div>
      {message !== null && <p className="error" role="status">{message}</p>}
      <div className="actions">
        <button type="submit" className="primary">Search</button>
        {etag !== null && <button type="button" onClick={cancel}>Cancel</button>}
      </div>
    </form>
  );
}

/** Выбор условий одной строкой для сравнения: порядок стран и позиций не важен. */
function selectionKey(countryCodes: string[], positionCodes: string[], format: WorkFormat | ''): string {
  return [...countryCodes].sort().join(',') + '|' + [...positionCodes].sort().join(',') + '|' + format;
}
