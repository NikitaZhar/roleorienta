import { type KeyboardEvent, useEffect, useRef, useState } from 'react';
import {
  ApiError, type CatalogItem, countries, positions, saveSearchCondition, searchCondition, type WorkFormat,
} from './api';
import { errorText, type MessageKey, useI18n } from './i18n';
import { PositionSuggestions } from './PositionSuggestions';

interface Props {
  /** Условия сохранены; {@code changed} — создана новая версия условий (ETag сменился). */
  onSaved: (changed: boolean) => void;
}

const FORMATS: { value: WorkFormat | ''; label: MessageKey }[] = [
  { value: '', label: 'format.any' },
  { value: 'OFFICE', label: 'format.office' },
  { value: 'REMOTE', label: 'format.remote' },
];

/** Позиций в условиях не больше (§126; как в API). */
const MAX_POSITIONS = 10;
/** Позиций в подписи фильтра: остальные — «+N». */
const NAMED_IN_CHIP = 2;

/** Позиция в условиях; {@code retired} — выключена администратором, остаётся в условиях (§160). */
interface Chosen {
  code: string;
  name: string;
  retired: boolean;
}

/** Выбор условий: страны, позиции, формат ('' — любой). */
interface Selection {
  countries: string[];
  positions: Chosen[];
  format: WorkFormat | '';
}

const NOTHING: Selection = { countries: [], positions: [], format: '' };

/** Открытый выбор фильтра; {@code null} — ни один. */
type Panel = 'countries' | 'positions' | 'format' | null;

/**
 * Условия поиска (бизнес-описание §3, §7.2; новый облик — стенограмма §171): строка фильтров-чипов «страны · позиции ·
 * формат» над результатами; чип открывает свой выбор во всплывающем окне, «Apply» сохраняет условия. Смена стран,
 * позиций или формата создаёт новую версию условий — список собирается заново, поэтому в окне предупреждение (решение
 * владельца 2026-10-21, вариант «A»). Позиции — из словаря (до 10, §126), выключенная администратором остаётся в
 * условиях зачёркнутой (§160); гибрида нет — гибридные вакансии выдаются при любом формате (§125). Условий нет —
 * «Set up your search»: выбор копится в окнах («Done») и сохраняется кнопкой «Search». Условия сменились в другом окне
 * ({@code 412}) — условия перечитываются, сообщение, список под ними начинается заново (аудит §154). «Suggest a
 * position» открывает окно предложения позиции (§161). Окно выбора закрывается щелчком мимо и клавишей Escape; при
 * сохранённых условиях несохранённый выбор при этом забывается. Версия условий — ETag; сохранение передаёт её в
 * If-Match. Лимита порции на экране нет (§120).
 */
export function ConditionsPage({ onSaved }: Props) {
  const { locale, t } = useI18n();
  const [catalog, setCatalog] = useState<CatalogItem[]>([]);
  const [saved, setSaved] = useState<Selection | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [draft, setDraft] = useState<Selection>(NOTHING);
  const [panel, setPanel] = useState<Panel>(null);
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<CatalogItem[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [suggesting, setSuggesting] = useState(false);
  const bar = useRef<HTMLDivElement>(null);

  async function load() {
    const [all, current] = await Promise.all([countries(), searchCondition()]);
    setCatalog(all);
    setEtag(current?.etag ?? null);
    if (current === null) {
      setSaved(null);
      setDraft(NOTHING);
    } else {
      const condition = current.data;
      const selection: Selection = {
        countries: condition.countries,
        positions: condition.positions.map((item) => ({ code: item.code, name: item.name, retired: !item.active })),
        format: condition.format ?? '',
      };
      setSaved(selection);
      setDraft(selection);
    }
    setLoaded(true);
  }

  useEffect(() => {
    load().catch((failure: unknown) => setMessage(errorText(failure, t)));
  }, []);

  /** Щелчок мимо строки фильтров закрывает окно выбора. */
  useEffect(() => {
    function closeOutside(event: MouseEvent) {
      if (bar.current !== null && !bar.current.contains(event.target as Node)) {
        close();
      }
    }
    document.addEventListener('mousedown', closeOutside);
    return () => document.removeEventListener('mousedown', closeOutside);
  }, [saved]);

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

  /** Открыть выбор (повторное нажатие — закрыть); при сохранённых условиях выбор начинается с них. */
  function toggle(next: Panel) {
    if (panel === next) {
      close();
      return;
    }
    if (saved !== null) {
      setDraft(saved);
    }
    setQuery('');
    setPanel(next);
  }

  /** Закрыть выбор: при сохранённых условиях несохранённое забывается; без условий — копится до «Search». */
  function close() {
    setPanel(null);
    setQuery('');
    if (saved !== null) {
      setDraft(saved);
    }
  }

  function onKey(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      close();
    }
  }

  function toggleCountry(code: string) {
    setDraft((current) => ({
      ...current,
      countries: current.countries.includes(code)
        ? current.countries.filter((item) => item !== code) : [...current.countries, code],
    }));
  }

  function togglePosition(item: Chosen) {
    setDraft((current) => {
      if (current.positions.some((other) => other.code === item.code)) {
        return { ...current, positions: current.positions.filter((other) => other.code !== item.code) };
      }
      return current.positions.length >= MAX_POSITIONS ? current
        : { ...current, positions: [...current.positions, item] };
    });
  }

  /** «Apply»: при сохранённых условиях — сохранить; без условий («Done») — выбор копится до «Search». */
  function apply() {
    if (saved === null) {
      setPanel(null);
      setQuery('');
      return;
    }
    submit(draft);
  }

  async function submit(selection: Selection) {
    if (selection.countries.length === 0 || selection.positions.length === 0) {
      setMessage(t('conditions.chooseBoth'));
      return;
    }
    setBusy(true);
    try {
      const result = await saveSearchCondition({
        countries: selection.countries,
        positions: selection.positions.map((item) => item.code),
        format: selection.format === '' ? null : selection.format,
        portionLimit: null,
      }, etag);
      const changed = saved === null || selectionKey(selection) !== selectionKey(saved);
      setSaved(selection);
      setDraft(selection);
      setEtag(result.etag);
      setMessage(null);
      setPanel(null);
      setQuery('');
      onSaved(changed);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 412) {
        setPanel(null);
        await load().catch((reload: unknown) => setMessage(errorText(reload, t)));
        setMessage(t('conditions.changedElsewhere'));
        onSaved(true);
        return;
      }
      setMessage(errorText(failure, t));
    } finally {
      setBusy(false);
    }
  }

  /** Название страны на языке интерфейса (Intl.DisplayNames браузера, §162); нет — как в справочнике сервера. */
  function countryName(code: string): string {
    return new Intl.DisplayNames([locale], { type: 'region' }).of(code)
      ?? catalog.find((item) => item.code === code)?.name ?? code;
  }

  function positionName(item: Chosen): string {
    return item.retired ? t('conditions.retired', { name: item.name }) : item.name;
  }

  if (!loaded) {
    return message === null ? <p className="muted">{t('app.loading')}</p> : <p className="error">{message}</p>;
  }

  const shown = panel === null && saved !== null ? saved : draft;
  const countriesLabel = shown.countries.length === 0 ? t('conditions.country')
    : shown.countries.map(countryName).join(', ');
  const positionsLabel = shown.positions.length === 0 ? t('conditions.position')
    : shown.positions.slice(0, NAMED_IN_CHIP).map(positionName).join(', ');
  const morePositions = shown.positions.length - NAMED_IN_CHIP;
  const formatLabel = t(FORMATS.find((item) => item.value === shown.format)?.label ?? 'format.any');
  const warning = saved !== null && <p className="popover-note">{t('conditions.rebuildWarning')}</p>;
  const footer = (note?: string) => (
    <div className="popover-foot">
      <span className="muted">{note}</span>
      <button type="button" className="primary" disabled={busy} onClick={apply}>
        {saved === null ? t('conditions.done') : t('conditions.apply')}
      </button>
    </div>
  );
  const chevron = (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
  );

  return (
    <div className={saved === null ? 'conditions setup' : 'conditions'}>
      {saved === null && (
        <>
          <h1>{t('conditions.setUpTitle')}</h1>
          <p className="muted">{t('conditions.setUpText')}</p>
        </>
      )}
      <div className="filter-bar" ref={bar} onKeyDown={onKey}>
        <div className="filter">
          <button type="button" className="chip" aria-expanded={panel === 'countries'} aria-haspopup="dialog"
            onClick={() => toggle('countries')}>
            <span className="chip-text">{countriesLabel}</span>{chevron}
          </button>
          {panel === 'countries' && (
            <div className="popover" role="dialog" aria-label={t('conditions.countries')}>
              <div className="options">
                {catalog.map((country) => (
                  <label key={country.code} className="option">
                    <input type="checkbox" checked={draft.countries.includes(country.code)}
                      onChange={() => toggleCountry(country.code)} />
                    {countryName(country.code)}
                  </label>
                ))}
              </div>
              {warning}
              {footer()}
            </div>
          )}
        </div>

        <div className="filter">
          <button type="button" className="chip" aria-expanded={panel === 'positions'} aria-haspopup="dialog"
            onClick={() => toggle('positions')}>
            <span className="chip-text">{positionsLabel}</span>
            {morePositions > 0 && <span className="muted">+{morePositions}</span>}
            {chevron}
          </button>
          {panel === 'positions' && (
            <div className="popover" role="dialog" aria-label={t('conditions.positions')}>
              <input type="search" autoFocus aria-label={t('conditions.lookFor')}
                placeholder={t('conditions.typeLetters')} value={query} onChange={(event) => setQuery(event.target.value)} />
              <div className="options">
                {draft.positions.map((item) => (
                  <label key={item.code} className="option">
                    <input type="checkbox" checked onChange={() => togglePosition(item)} />
                    <span className={item.retired ? 'retired' : undefined}>{item.name}</span>
                    {item.retired && <span className="muted">{t('conditions.retiredMark')}</span>}
                  </label>
                ))}
                {found.filter((item) => !draft.positions.some((other) => other.code === item.code)).map((item) => (
                  <label key={item.code} className="option">
                    <input type="checkbox" checked={false} disabled={draft.positions.length >= MAX_POSITIONS}
                      onChange={() => togglePosition({ code: item.code, name: item.name, retired: false })} />
                    {item.name}
                  </label>
                ))}
              </div>
              {warning}
              {footer(t('conditions.chosen', { count: draft.positions.length, max: MAX_POSITIONS }))}
            </div>
          )}
        </div>

        <div className="filter">
          <button type="button" className="chip" aria-expanded={panel === 'format'} aria-haspopup="dialog"
            onClick={() => toggle('format')}>
            <span className="chip-text">{formatLabel}</span>{chevron}
          </button>
          {panel === 'format' && (
            <div className="popover small" role="dialog" aria-label={t('conditions.workFormat')}>
              <div className="options">
                {FORMATS.map((item) => (
                  <label key={item.value} className="option">
                    <input type="radio" name="format" checked={draft.format === item.value}
                      onChange={() => setDraft((current) => ({ ...current, format: item.value }))} />
                    {t(item.label)}
                  </label>
                ))}
              </div>
              {warning}
              {footer()}
            </div>
          )}
        </div>

        {saved === null && (
          <button type="button" className="primary" disabled={busy} onClick={() => submit(draft)}>
            {t('conditions.search')}
          </button>
        )}
        <button type="button" className="link" onClick={() => setSuggesting(true)}>{t('suggest.link')}</button>
      </div>
      {message !== null && <p className="error" role="status">{message}</p>}
      {suggesting && <PositionSuggestions onClose={() => setSuggesting(false)} />}
    </div>
  );
}

/** Выбор условий одной строкой для сравнения: порядок стран и позиций не важен. */
function selectionKey(selection: Selection): string {
  return [...selection.countries].sort().join(',') + '|'
    + selection.positions.map((item) => item.code).sort().join(',') + '|' + selection.format;
}
