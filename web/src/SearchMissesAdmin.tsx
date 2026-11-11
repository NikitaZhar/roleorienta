import { useEffect, useState } from 'react';
import {
  type ChosenWithoutVacancies, chosenWithoutVacancies, forgetSearchMiss, type GroupHint, mapProfesiaPosition,
  occupations,
  type ProfesiaPosition, profesiaPositions, type SearchMiss, searchMisses,
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

/**
 * Позиции profesia.sk без группы (§305): объявления profesia.sk с такой позицией не находит ни один поиск, пока
 * администратор не выберет группу ISCO-08. Больше вакансий — первыми; подсказка групп — кнопками (та же подсказка, что
 * у пользователя); группу можно и ввести — название или 4 цифры, как в форме словаря. «Save» — позиция уходит из списка.
 * Лучшая подсказка сразу в поле (верна — один «Save»); остальные — метками под полем, выбранная выделена (aria-pressed),
 * с причиной: позиция словаря, доля объявлений, название ESCO.
 */
export function ProfesiaPositionsAdmin() {
  const [items, setItems] = useState<ProfesiaPosition[] | null>(null);
  const [groups, setGroups] = useState<{ code: string; name: string }[]>([]);
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);

  async function load() {
    setMessage(null);
    try {
      const loaded = await profesiaPositions();
      setItems(loaded);
      setChosen(Object.fromEntries(loaded.filter((item) => item.hints.length > 0)
        .map((item) => [item.slug, label(item.hints[0])])));
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  useEffect(() => {
    load();
    occupations().then((directions) => setGroups(directions.flatMap((direction) => direction.groups)
      .map((group) => ({ code: group.code, name: group.name }))), () => setGroups([]));
  }, []);

  async function save(slug: string) {
    const group = (chosen[slug] ?? '').trim().substring(0, 4);
    setMessage(null);
    if (!/^[0-9]{4}$/.test(group)) {
      setMessage('Choose a group: type a name or 4 digits and pick it from the list.');
      return;
    }
    try {
      await mapProfesiaPosition(slug, group);
      setItems((items ?? []).filter((item) => item.slug !== slug));
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  return (
    <section>
      <h2>profesia.sk positions without a group</h2>
      <p className="muted">Ads with these positions are found by no search. The best suggestion is already in the
        field: if it is right, press Save; otherwise pick another suggestion or type a group. The ads are found by
        searches of that group at once.</p>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {items !== null && (items.length === 0 ? <p className="muted">None: every position has a group.</p> : (
        <div className="scroll">
          <table className="companies">
            <thead>
              <tr><th>Position on profesia.sk</th><th>Vacancies</th><th>Group</th>
                <th><span className="sr-only">Actions</span></th></tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.slug}>
                  <td>{item.name}</td>
                  <td>{item.vacancies}</td>
                  <td>
                    <div className="group-choice">
                      <input aria-label={'Group for ' + item.name} list="profesia-isco-groups"
                        placeholder="Group: name or 4 digits" value={chosen[item.slug] ?? ''}
                        onChange={(event) => setChosen({ ...chosen, [item.slug]: event.target.value })} />
                      {item.hints.length > 0 && (
                        <div className="position-filter">
                          {item.hints.map((hint) => {
                            const selected = (chosen[item.slug] ?? '').startsWith(hint.code);
                            return (
                              <button key={hint.code} type="button" title={hint.reason}
                                className={selected ? 'chip current' : 'chip'} aria-pressed={selected}
                                onClick={() => setChosen({ ...chosen, [item.slug]: label(hint) })}>
                                {hint.code} {hint.name} <span className="chip-count">· {hint.reason}</span>
                              </button>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  </td>
                  <td className="row-actions nowrap">
                    <button type="button" onClick={() => save(item.slug)}>Save</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
      <datalist id="profesia-isco-groups">
        {groups.map((group) => <option key={group.code} value={group.code + ' ' + group.name} />)}
      </datalist>
      <button type="button" onClick={load}>Refresh</button>
    </section>
  );
}

/** Подсказка в поле группы: «2512 Software developers» (первые 4 знака — код, как в форме словаря). */
function label(hint: GroupHint): string {
  return hint.code + ' ' + hint.name;
}
