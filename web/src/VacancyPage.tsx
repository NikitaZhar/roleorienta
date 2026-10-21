import { useEffect, useRef, useState } from 'react';
import { setStatus, type StatusEvent, vacancy, type VacancyDetails } from './api';
import { countriesText, dateText, HISTORY_LABELS, openedText, STATUS_LABELS } from './format';

interface Props {
  id: number;
  /** Подпись возврата: к какому разделу («← Back to results», «← Back to applications» …). */
  backLabel: string;
  /** Вернуться к разделу; {@code changed} — статус вакансии изменился, список нужно перечитать. */
  onBack: (changed: boolean) => void;
}

/** Статусы, которые выбирает пользователь, в порядке списка; «Не интересно» — отдельно, за чертой (§147). */
const CHOICES = ['APPLIED', 'INTERVIEW', 'REJECTED'] as const;

/**
 * Сведения о вакансии (бизнес-описание §6, экран §147): позиция, работодатель и агентство; под ними — текущий статус
 * с датой и выпадающий список статусов: «Viewed» (не выбирается — ставит приложение при открытии новой вакансии),
 * «CV sent», «Interview», «Rejected», за чертой «Not interested» (§156). Ниже — страна (не определена — «not
 * specified»), территория удалённой работы, дата открытия («found …», если работодатель даты не дал, §127), ссылка на
 * первичную публикацию и история с датами, раньше сверху. После смены статуса экран остаётся, без сообщения, и
 * перечитывается; при возврате список перечитывается — вакансия, ушедшая в другой раздел, из него пропадает.
 * Состояние и дата последней проверки не показываются (решение владельца 2026-10-20, §146). Формат и языки — срез 5.
 */
export function VacancyPage({ id, backLabel, onBack }: Props) {
  const [details, setDetails] = useState<VacancyDetails | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** Статус менялся за время открытия (в том числе «Viewed» при открытии новой). */
  const changed = useRef(false);

  useEffect(() => {
    async function open() {
      let next = await vacancy(id);
      if (next.publication.timeline.status.code === 'NEW') {
        await setStatus(id, 'VIEWED');
        changed.current = true;
        next = await vacancy(id);
      }
      setDetails(next);
    }
    open().catch((failure: Error) => setMessage(failure.message));
  }, [id]);

  async function choose(status: StatusEvent) {
    setBusy(true);
    try {
      await setStatus(id, status);
      changed.current = true;
      setDetails(await vacancy(id));
    } catch (failure) {
      setMessage((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const back = <button type="button" onClick={() => onBack(changed.current)}>{backLabel}</button>;
  if (message !== null) {
    return <p className="error">{message} {back}</p>;
  }
  if (details === null) {
    return <p>Loading…</p>;
  }
  const { parties, work, publication } = details;
  const status = publication.timeline.status;
  return (
    <article className="details">
      {back}
      <h2>{details.position}</h2>
      <dl>
        <dt>Employer</dt>
        <dd>{parties.employer ?? 'not specified'}</dd>
        {parties.agency !== null && (<><dt>Agency</dt><dd>{parties.agency}</dd></>)}
      </dl>
      <div className="status">
        <label>
          <span className="muted">Status</span>{' '}
          <select value={status.code} disabled={busy} onChange={(event) => choose(event.target.value as StatusEvent)}>
            {status.code === 'NEW' && <option value="NEW" disabled>{STATUS_LABELS.NEW}</option>}
            <option value="VIEWED" disabled>{STATUS_LABELS.VIEWED}</option>
            {CHOICES.map((choice) => <option key={choice} value={choice}>{STATUS_LABELS[choice]}</option>)}
            <option value="" disabled>──────────</option>
            <option value="NOT_INTERESTED">{STATUS_LABELS.NOT_INTERESTED}</option>
          </select>
        </label>
        <span className="muted">{[dateText(status.at), status.closed ? 'closed by employer' : '']
          .filter((part) => part !== '').join(' · ')}</span>
      </div>
      <dl>
        <dt>Country</dt>
        <dd>{countriesText(work)}</dd>
        {work.remoteTerritory !== null && (<><dt>Remote from</dt><dd>{work.remoteTerritory.join(', ')}</dd></>)}
        <dt>Opened</dt>
        <dd>{openedText(publication.timeline.opened)}</dd>
      </dl>
      {publication.url !== null && (
        <div className="actions">
          <a className="button" href={publication.url} target="_blank" rel="noopener noreferrer">Open the posting</a>
        </div>
      )}
      <h3>History</h3>
      <ol className="history">
        {publication.history.map((line, index) => (
          <li key={index}><span className="date">{dateText(line.at)}</span>{HISTORY_LABELS[line.event]}</li>
        ))}
      </ol>
    </article>
  );
}
