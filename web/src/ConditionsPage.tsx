import { type FormEvent, useEffect, useState } from 'react';
import {
  ApiError, type CatalogItem, countries, type PositionOption, positions, saveSearchCondition, searchCondition,
  type WorkFormat,
} from './api';
import { errorText, type MessageKey, useI18n } from './i18n';
import { PositionCatalog } from './PositionCatalog';

interface Props {
  /** Условия сохранены; {@code changed} — создана новая версия условий (ETag сменился). */
  onSaved: (changed: boolean) => void;
}

const FORMATS: { value: WorkFormat | ''; label: MessageKey }[] = [
  { value: '', label: 'format.any' },
  { value: 'OFFICE', label: 'format.office' },
  { value: 'REMOTE', label: 'format.remote' },
];

/** Позиций и групп в условиях вместе не больше (§126; как в API). */
const MAX_POSITIONS = 10;

/**
 * Условия поиска (бизнес-описание §3, §7.2): страны, позиции (одна или несколько — вакансия подходит к любой из
 * них; выбранные — метками с «×», §126), формат работы. Позиция (§204) — точная позиция словаря («Java developer») или
 * группа ISCO-08 («Разработчики ПО»: все вакансии группы — пометкой в подсказке). Выбор — вводом (подсказка после
 * двух букв) или в полном каталоге «Full catalog» (направления → группы → позиции). Названия групп — на языке
 * интерфейса: смена языка перечитывает условия. Формат работы (Any — все форматы; гибрида нет —
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
  const { language, locale, t } = useI18n();
  const [catalog, setCatalog] = useState<CatalogItem[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [chosen, setChosen] = useState<CatalogItem[]>([]);
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<PositionOption[]>([]);
  const [browsing, setBrowsing] = useState(false);
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
    setBrowsing(false);
    if (current === null) {
      setSelected([]);
      setChosen([]);
      setFormat('');
      setSavedSelection(null);
      setEditing(true);
    } else {
      const condition = current.data;
      setSavedSelection(selectionKey(condition.countries, condition.positions.map((item) => item.code),
        condition.format ?? ''));
      setSelected(condition.countries);
      setFormat(condition.format ?? '');
      // Выключенная администратором позиция остаётся в условиях названием с пометкой (§160, аудит §165).
      setChosen(condition.positions.map((item) => ({ code: item.code,
        name: item.active ? item.name : t('conditions.retired', { name: item.name }) })));
      setEditing(false);
    }
    setLoaded(true);
  }

  function cancel() {
    setMessage(null);
    load().catch((failure: unknown) => setMessage(errorText(failure, t)));
  }

  useEffect(() => {
    load().catch((failure: unknown) => setMessage(errorText(failure, t)));
  }, [language]);

  function choose(item: CatalogItem) {
    setChosen((current) => current.length >= MAX_POSITIONS || current.some((other) => other.code === item.code)
      ? current : [...current, { code: item.code, name: item.name }]);
    setQuery('');
  }

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
      }, (failure: unknown) => {
        if (current) {
          setMessage(errorText(failure, t));
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
      setMessage(t('conditions.chooseBoth'));
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
      setBrowsing(false);
      onSaved(changed);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 412) {
        setMessage(t('conditions.changedElsewhere'));
        await load().catch((reload: unknown) => setMessage(errorText(reload, t)));
        setEditing(true);
        onSaved(true);
        return;
      }
      setMessage(errorText(failure, t));
    }
  }

  /** Название страны на языке интерфейса (Intl.DisplayNames браузера, §162); нет — как в справочнике сервера. */
  function countryName(code: string): string {
    return new Intl.DisplayNames([locale], { type: 'region' }).of(code)
      ?? catalog.find((item) => item.code === code)?.name ?? code;
  }

  if (!loaded) {
    return message === null ? <p>{t('app.loading')}</p> : <p className="error">{message}</p>;
  }
  if (!editing) {
    return (
      <div className="filters-summary">
        <dl className="criteria" aria-label={t('nav.search')}>
          <div>
            <dt>{selected.length > 1 ? t('conditions.countries') : t('conditions.country')}</dt>
            <dd><ul className="chips">
              {selected.map((code) => <li key={code}>{countryName(code)}</li>)}
            </ul></dd>
          </div>
          <div>
            <dt>{chosen.length > 1 ? t('conditions.positions') : t('conditions.position')}</dt>
            <dd><ul className="chips">{chosen.map((item) => <li key={item.code}>{item.name}</li>)}</ul></dd>
          </div>
          <div>
            <dt>{t('conditions.format')}</dt>
            <dd><ul className="chips"><li>{t(FORMATS.find((item) => item.value === format)?.label ?? 'format.any')}</li></ul></dd>
          </div>
        </dl>
        <button type="button" onClick={() => setEditing(true)}>{t('conditions.edit')}</button>
      </div>
    );
  }
  return (
    <form className="filters" onSubmit={save}>
      {etag === null && <p className="lead">{t('conditions.empty')}</p>}
      <div className="filters-row">
        <fieldset>
          <legend>{t('conditions.countries')}</legend>
          {catalog.map((country) => (
            <label key={country.code} className="inline">
              <input type="checkbox" checked={selected.includes(country.code)}
                onChange={() => toggle(country.code)} />
              {countryName(country.code)}
            </label>
          ))}
        </fieldset>
        <div className="positions">
          <label>
            {t('conditions.lookFor')}
            <input type="search" placeholder={t('conditions.typeLetters')} value={query}
              disabled={chosen.length >= MAX_POSITIONS} onChange={(event) => setQuery(event.target.value)} />
          </label>
          {chosen.length > 0 && (
            <ul className="chips">
              {chosen.map((item) => (
                <li key={item.code}>
                  {item.name}
                  <button type="button" aria-label={t('conditions.remove', { name: item.name })}
                    onClick={() => setChosen((current) => current.filter((other) => other.code !== item.code))}>×</button>
                </li>
              ))}
            </ul>
          )}
          {found.length > 0 && (
            <ul className="choices">
              {found.filter((item) => !chosen.some((other) => other.code === item.code)).map((item) => (
                <li key={item.code}>
                  <button type="button" onClick={() => choose(item)}>
                    {item.name}
                    {item.group && <span className="muted"> · {t('conditions.wholeGroup')}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )}
          <button type="button" className="link" aria-expanded={browsing} onClick={() => setBrowsing(!browsing)}>
            {t(browsing ? 'conditions.browseClose' : 'conditions.browse')}
          </button>
          {browsing && (
            <PositionCatalog chosen={chosen.map((item) => item.code)} onChoose={choose} />
          )}
        </div>
        <div>
          <label>
            {t('conditions.workFormat')}
            <select value={format} onChange={(event) => setFormat(event.target.value as WorkFormat | '')}>
              {FORMATS.map((item) => <option key={item.value} value={item.value}>{t(item.label)}</option>)}
            </select>
          </label>
          {format !== '' && <p className="muted field-note">{t('conditions.formatNote')}</p>}
        </div>
      </div>
      {message !== null && <p className="error" role="status">{message}</p>}
      <div className="actions">
        <button type="submit" className="primary">{t('conditions.search')}</button>
        {etag !== null && <button type="button" onClick={cancel}>{t('conditions.cancel')}</button>}
      </div>
    </form>
  );
}

/** Выбор условий одной строкой для сравнения: порядок стран и позиций не важен. */
function selectionKey(countryCodes: string[], positionCodes: string[], format: WorkFormat | ''): string {
  return [...countryCodes].sort().join(',') + '|' + [...positionCodes].sort().join(',') + '|' + format;
}
