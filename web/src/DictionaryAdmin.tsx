import { type FormEvent, useEffect, useState } from 'react';
import {
  addPosition, changeDictionarySettings, changePosition, dictionary, type Dictionary, type DictionaryPosition,
  type PositionTerms,
} from './api';

/** Поля названий по видам: ключ, подпись, подсказка. */
const TERM_FIELDS: { key: keyof PositionTerms; label: string; hint: string }[] = [
  { key: 'titles', label: 'Equivalent titles', hint: 'At least one: java developer, java engineer…' },
  { key: 'roles', label: 'Roles', hint: 'In the title together with a keyword: developer, engineer…' },
  { key: 'keywords', label: 'Specialization keywords', hint: 'In the title with a role, or in the text: java, spring…' },
  { key: 'exclude', label: 'Excluding words', hint: 'In the title they exclude the position: tester, intern…' },
];

/** Пустые названия новой позиции. */
const NO_TERMS: PositionTerms = { titles: [], roles: [], keywords: [], exclude: [] };

/** Позиция в форме: код и названия; {@code id} нет — новая. */
interface Draft {
  id: number | null;
  code: string;
  name: string;
  active: boolean;
  terms: Record<keyof PositionTerms, string>;
}

/**
 * Словарь позиций для администратора (§160): таблица позиций (и выключенных; на узком экране — прокрутка таблицы, а не
 * перенос по буквам), форма позиции — по полю на вид названий, одно слово или словосочетание в строке; «New
 * position»; общие настройки. Изменение записывается кнопкой «Save»: выбор позиций у пользователей меняется сразу,
 * вакансии пересчитываются на следующем такте сопоставления (до часа); номер версии словаря — справочно. Регистр и диакритика при
 * сопоставлении не важны, повторы убирает сервер.
 */
export function DictionaryAdmin() {
  const [data, setData] = useState<Dictionary | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function load() {
    setMessage(null);
    dictionary().then(setData, (failure: Error) => setMessage(failure.message));
  }

  useEffect(load, []);

  async function save(action: () => Promise<Dictionary>, after: () => void) {
    setBusy(true);
    setMessage(null);
    try {
      setData(await action());
      after();
    } catch (failure) {
      setMessage((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (draft === null) {
      return;
    }
    const terms = parse(draft.terms);
    save(() => (draft.id === null ? addPosition(draft.code.trim(), draft.name, terms)
      : changePosition(draft.id, draft.name, draft.active, terms)), () => setDraft(null));
  }

  if (data === null) {
    return (
      <section>
        <h2>Position dictionary</h2>
        {message === null ? <p className="muted">Loading…</p> : (
          <><p className="error" role="alert">{message}</p><button type="button" onClick={load}>Retry</button></>
        )}
      </section>
    );
  }
  return (
    <section>
      <h2>Position dictionary</h2>
      <p className="muted">Changes take effect when you press Save. Users see the new list of positions at once;
        vacancies are matched to the changed dictionary within an hour. Dictionary version {data.version}.</p>
      {message !== null && <p className="error" role="alert">{message}</p>}
      <div className="scroll">
      <table className="companies dictionary">
        <thead>
          <tr>
            <th>Position</th><th>Code</th><th>Titles</th><th className="nowrap">State</th>
            <th><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {data.positions.map((position) => (
            <tr key={position.id}>
              <td>{position.name}</td>
              <td className="muted nowrap">{position.code}</td>
              <td>{position.terms.titles.join(', ')}</td>
              <td className="nowrap">{position.active ? 'active' : 'retired'}</td>
              <td className="row-actions nowrap">
                <button type="button" disabled={busy} onClick={() => setDraft(toDraft(position))}>Edit</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      {draft === null ? (
        <button type="button" disabled={busy} onClick={() => setDraft(newDraft())}>New position</button>
      ) : (
        <form className="dictionary-form" onSubmit={submit}>
          <h3>{draft.id === null ? 'New position' : 'Edit ' + draft.code}</h3>
          {draft.id === null && (
            <label>Code <span className="muted">(lowercase letters, digits and hyphens; cannot be changed later)</span>
              <input required value={draft.code} pattern="[a-z0-9]+(-[a-z0-9]+)*"
                onChange={(event) => setDraft({ ...draft, code: event.target.value })} />
            </label>
          )}
          <label>Name
            <input required value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
          </label>
          {draft.id !== null && (
            <label className="check">
              <input type="checkbox" checked={draft.active}
                onChange={(event) => setDraft({ ...draft, active: event.target.checked })} /> Active
              <span className="muted">— unchecked: retired. Not offered to users and not matched to vacancies; stays
                in the search conditions of users who already chose it. A position is never deleted.</span>
            </label>
          )}
          {TERM_FIELDS.map((field) => (
            <label key={field.key}>{field.label} <span className="muted">— {field.hint} One per line.</span>
              <textarea rows={4} value={draft.terms[field.key]}
                onChange={(event) => setDraft({ ...draft, terms: { ...draft.terms, [field.key]: event.target.value } })} />
            </label>
          ))}
          <div className="actions">
            <button type="submit" className="primary" disabled={busy}>Save</button>
            <button type="button" disabled={busy} onClick={() => setDraft(null)}>Cancel</button>
          </div>
        </form>
      )}
      <SettingsForm data={data} busy={busy}
        onSave={(contentHits, others) => save(() => changeDictionarySettings(contentHits, others), () => undefined)} />
    </section>
  );
}

/** Общие настройки словаря: число повторов признака в тексте и признаки специализаций вне словаря. */
function SettingsForm({ data, busy, onSave }: {
  data: Dictionary;
  busy: boolean;
  onSave: (contentHits: number, others: string[]) => void;
}) {
  const [contentHits, setContentHits] = useState(String(data.contentHits));
  const [others, setOthers] = useState(data.otherSpecializations.join('\n'));

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSave(Number(contentHits), lines(others));
  }

  return (
    <form className="dictionary-form" onSubmit={submit}>
      <h3>General settings</h3>
      <label>Keyword occurrences in the text <span className="muted">— with a role in the title, a specialization
        keyword must occur in the text at least this many times</span>
        <input type="number" min={1} max={100} required value={contentHits}
          onChange={(event) => setContentHits(event.target.value)} />
      </label>
      <label>Other specializations <span className="muted">— not in the dictionary (ruby, php…): with one of them in
        the title, the text rule is not applied. One per line.</span>
        <textarea rows={4} value={others} onChange={(event) => setOthers(event.target.value)} />
      </label>
      <div className="actions">
        <button type="submit" disabled={busy}>Save settings</button>
      </div>
    </form>
  );
}

function newDraft(): Draft {
  return toDraft({ id: -1, code: '', name: '', active: true, terms: NO_TERMS }, true);
}

function toDraft(position: DictionaryPosition, isNew = false): Draft {
  return {
    id: isNew ? null : position.id,
    code: position.code,
    name: position.name,
    active: position.active,
    terms: {
      titles: position.terms.titles.join('\n'),
      roles: position.terms.roles.join('\n'),
      keywords: position.terms.keywords.join('\n'),
      exclude: position.terms.exclude.join('\n'),
    },
  };
}

function parse(terms: Record<keyof PositionTerms, string>): PositionTerms {
  return { titles: lines(terms.titles), roles: lines(terms.roles), keywords: lines(terms.keywords),
    exclude: lines(terms.exclude) };
}

/** Строки поля: без пробелов по краям, без пустых. */
function lines(text: string): string[] {
  return text.split('\n').map((line) => line.trim()).filter((line) => line !== '');
}
