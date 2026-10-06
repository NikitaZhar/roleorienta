import { useEffect, useState } from 'react';
import { markUnsuitable, unmarkUnsuitable, vacancy, type VacancyDetails } from './api';
import { countriesText, dateText, formatText } from './format';

interface Props {
  id: number;
  onBack: () => void;
}

const STATES: Record<string, string> = {
  ACTIVE: 'active',
  NEEDS_RECHECK: 'needs recheck (source temporarily unavailable)',
  CLOSED: 'closed',
};

/**
 * Сведения о вакансии (бизнес-описание §6): позиция, работодатель и агентство, страна и формат с пометками
 * неопределённости, ссылка на первичную публикацию, даты проверки, состояние; отметка «не подходит» и её снятие.
 */
export function VacancyPage({ id, onBack }: Props) {
  const [details, setDetails] = useState<VacancyDetails | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    vacancy(id).then(setDetails, (failure: Error) => setMessage(failure.message));
  }, [id]);

  async function toggle() {
    if (details === null) {
      return;
    }
    try {
      if (details.publication.unsuitable) {
        await unmarkUnsuitable(id);
      } else {
        await markUnsuitable(id);
      }
      onBack();
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  if (message !== null) {
    return <p className="error">{message} <button type="button" onClick={onBack}>Back</button></p>;
  }
  if (details === null) {
    return <p>Loading…</p>;
  }
  const { parties, work, publication } = details;
  return (
    <article className="details">
      <button type="button" onClick={onBack}>← Back</button>
      <h2>{details.position}</h2>
      <dl>
        <dt>Employer</dt>
        <dd>{parties.employer ?? 'not specified'}</dd>
        {parties.agency !== null && (<><dt>Agency</dt><dd>{parties.agency}</dd></>)}
        <dt>Country</dt>
        <dd>{countriesText(work)}{work.countryUncertain && ' (not clear from the posting)'}</dd>
        <dt>Format</dt>
        <dd>{formatText(work)}{work.formatUncertain && ' (not clear from the posting)'}</dd>
        {work.remoteTerritory !== null && (<><dt>Remote from</dt><dd>{work.remoteTerritory.join(', ')}</dd></>)}
        <dt>State</dt>
        <dd>{STATES[publication.state] ?? publication.state}</dd>
        <dt>Found</dt>
        <dd>{dateText(publication.firstSeenAt)}</dd>
        <dt>Last confirmed</dt>
        <dd>{dateText(publication.lastConfirmedAt)}</dd>
      </dl>
      <div className="actions">
        {publication.url !== null && (
          <a className="button" href={publication.url} target="_blank" rel="noopener noreferrer">Open the posting</a>
        )}
        <button type="button" onClick={toggle}>
          {publication.unsuitable ? 'Return to the list' : 'Not suitable'}
        </button>
      </div>
    </article>
  );
}
